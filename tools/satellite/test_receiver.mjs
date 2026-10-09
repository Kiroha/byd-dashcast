// Exercise the bundled viewer's async ordering and teardown without a browser or new dependencies.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {test} from 'node:test';
import vm from 'node:vm';

const html = readFileSync(new URL('../../app/src/main/assets/satellite_receiver.html', import.meta.url), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

function harness({supported = true, holdFirstOffer = false} = {}) {
  const peers = [], replies = [], intervals = new Map(), timeouts = new Map();
  let timer = 0, now = 0, releaseFirst;
  const video = {srcObject: null, play: () => Promise.resolve()};
  const status = {style: {display: 'flex'}};
  class Peer {
    constructor(config) { this.config = config; this.ice = []; this.closed = false; this.frames = 0; peers.push(this); }
    async setRemoteDescription(description) {
      if (holdFirstOffer && peers[0] === this) await new Promise(resolve => { releaseFirst = resolve; });
      this.remoteDescription = description;
    }
    async createAnswer() { return {type: 'answer', sdp: 'test-answer'}; }
    async setLocalDescription(description) { this.localDescription = description; }
    async addIceCandidate(candidate) { this.ice.push(candidate); }
    async getStats() { return new Map([['inbound', {type: 'inbound-rtp', kind: 'video', framesDecoded: this.frames}]]); }
    close() { this.closed = true; }
  }
  const context = vm.createContext({
    window: {}, document: {getElementById: id => id === 'video' ? video : status},
    MediaStream: class {constructor(tracks) { this.tracks = tracks; }},
    DashCastVideo: {ready: value => replies.push({ready: value}), send: (session, raw) => replies.push({session, ...JSON.parse(raw)})},
    performance: {now: () => now},
    setInterval: callback => { intervals.set(++timer, callback); return timer; },
    clearInterval: id => intervals.delete(id),
    setTimeout: callback => { timeouts.set(++timer, callback); return timer; },
    clearTimeout: id => timeouts.delete(id),
    ...(supported ? {RTCPeerConnection: Peer} : {}),
  });
  vm.runInContext(script, context);
  return {context, peers, replies, video, status, intervals, timeouts,
    offer: (session = 'session', negotiation = 'first') => context.window.receiveSignal(session,
      JSON.stringify({type: 'video.offer', negotiation, sdp: 'test-offer'})),
    signal: (message, session = 'session') => context.window.receiveSignal(session, JSON.stringify(message)),
    reset: () => context.window.resetReceiver(),
    releaseFirst: () => releaseFirst(),
    advance: async millis => { now = millis; for (const callback of [...intervals.values()]) await callback(); },
  };
}
async function flush() { for (let i = 0; i < 3; i++) await new Promise(resolve => setImmediate(resolve)); }

test('missing WebRTC is reported before a viewer is advertised', () => {
  assert.deepEqual(harness({supported: false}).replies, [{ready: false}]);
});

test('offer and immediately queued ICE preserve order across awaits', async () => {
  const h = harness();
  h.offer();
  h.signal({type: 'video.ice', negotiation: 'first', candidate: 'candidate:test', sdpMid: '0', sdpMLineIndex: 0});
  await flush();
  assert.equal(h.peers.length, 1);
  assert.equal(h.peers[0].ice.length, 1);
  assert.equal(h.peers[0].remoteDescription.type, 'offer');
  assert.equal(h.replies.filter(reply => reply.type === 'video.answer').length, 1);
  assert.equal(h.peers[0].config.iceServers.length, 0);
});

test('external reset permits a new sender while old SDP is still pending', async () => {
  const h = harness({holdFirstOffer: true});
  h.offer('old', 'old');
  await flush();
  h.reset();
  h.offer('new', 'new');
  await flush();
  assert.equal(h.peers[0].closed, true);
  assert.equal(h.replies.filter(reply => reply.type === 'video.answer').length, 1);
  assert.equal(h.replies.find(reply => reply.type === 'video.answer').session, 'new');
  h.releaseFirst();
  await flush();
  assert.equal(h.peers[1].closed, false);
  assert.equal(h.replies.filter(reply => reply.type === 'video.answer').length, 1);
});

test('ICE from stale sessions and negotiations is discarded', async () => {
  const h = harness(); h.offer(); await flush();
  const ice = {type: 'video.ice', negotiation: 'first', candidate: 'candidate:test', sdpMid: '0', sdpMLineIndex: 0};
  h.signal(ice, 'old'); h.signal({...ice, negotiation: 'old'});
  await flush(); assert.equal(h.peers[0].ice.length, 0);
});

test('video stop releases connection, timers and displayed image', async () => {
  const h = harness(); h.offer(); await flush();
  h.peers[0].ontrack({track: {kind: 'video'}});
  assert.notEqual(h.video.srcObject, null);
  h.signal({type: 'video.stop', negotiation: 'first'}); await flush();
  assert.equal(h.peers[0].closed, true); assert.equal(h.video.srcObject, null);
  assert.equal(h.intervals.size, 0); assert.equal(h.status.style.display, 'flex');
});

test('audio tracks do not enter the displayed media stream', async () => {
  const h = harness(); h.offer(); await flush();
  h.peers[0].ontrack({track: {kind: 'audio'}});
  assert.equal(h.video.srcObject, null);
});

test('zero decoded frames do not hide the waiting screen or renew liveness', async () => {
  const h = harness(); h.offer(); await flush();
  await h.advance(1000); assert.equal(h.status.style.display, 'flex');
  await h.advance(6001); assert.equal(h.peers[0].closed, true);
  assert.equal(h.replies.at(-1).code, 'video_timeout');
});

test('a previously decoded map is cleared when decoder progress stops', async () => {
  const h = harness(); h.offer(); await flush();
  h.peers[0].frames = 5; await h.advance(1000);
  assert.equal(h.status.style.display, 'none');
  await h.advance(7001);
  assert.equal(h.peers[0].closed, true); assert.equal(h.video.srcObject, null);
});

test('async signalling overflow clears the old peer without an unbounded queue', async () => {
  const h = harness({holdFirstOffer: true}); h.offer(); await flush();
  for (let i = 0; i < 40; i++) h.signal({type: 'video.ice', negotiation: 'first', candidate: 'candidate:test', sdpMid: '0', sdpMLineIndex: 0});
  await flush();
  assert.equal(h.peers[0].closed, true);
  assert.equal(h.replies.some(reply => reply.code === 'signalling_overflow'), true);
  h.releaseFirst(); await flush();
  assert.equal(h.peers[0].ice.length, 0);
});

test('negotiation promises have a deadline and reset the viewer on timeout', async () => {
  const h = harness({holdFirstOffer: true}); h.offer(); await flush();
  for (const timeout of [...h.timeouts.values()]) timeout();
  await flush();
  assert.equal(h.peers[0].closed, true);
  assert.equal(h.replies.at(-1).code, 'negotiation_failed');
});
