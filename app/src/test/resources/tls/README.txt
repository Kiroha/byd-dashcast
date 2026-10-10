PUBLIC TEST IDENTITY ONLY

test-only-server.p12 (password: test-only) and receiver.pem are generated, public
test fixtures for loopback TLS handshake tests. They are not production secrets,
not Android signing keys, and are under src/test so they are not shipped in APKs.
