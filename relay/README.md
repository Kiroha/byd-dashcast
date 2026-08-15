# DashCast report relay

A single Azure Function. The car POSTs a report here; this forwards it to Telegram.

## Why it exists

AUD-001 took the Telegram bot token out of the APK. That closed a real hole — every published
release since versionCode 441 carried the token in its DEX, extractable with `unzip` and `strings`,
and rotating it meant shipping a new version to every car. But it opened another: a device that
agreed to send reports had nothing to send them with.

Every way of closing that gap without a relay puts a secret back in the binary. Encrypting it does
not help — the key has to be in there too, and a debugger reads the plaintext at the moment of use.
Fetching it from a URL only adds a hop. The relay is the only arrangement where **a decompiled APK
yields nothing worth stealing**: an ordinary HTTPS endpoint, and the token nowhere near it.

What this buys, concretely:

| | Before | With the relay |
|---|---|---|
| Token in the APK | yes, every release | no |
| Rotating the token | a release, to every car | an app setting and a restart |
| Revoking access | impossible; old APKs keep working | delete the function |
| A leaked artefact grants | posting as the bot, reading what is addressed to it | posting to this endpoint |

## The route is anonymous, on purpose

A function key would be a secret in the APK again. A weaker one than a bot token, but the same
shape of problem, and it would put a provisioning step back in front of a user for a credential
whose leak only enables spam.

So the URL is public by design, and the thing that matters is that it **grants nothing**: no read of
anyone's report, no impersonation of the bot beyond posting into the group it already posts into.

The residual risk is abuse, and the answers are operational:

- the function rejects anything that does not look like a report (size floor and ceiling, filename
  shape, known topic);
- **set a daily quota** so the worst case is the relay going quiet rather than a bill —
  `az functionapp update -g <rg> -n <app> --set dailyMemoryTimeQuota=50000`;
- if the URL is ever abused, redeploy under a different route name and ship it. The bot token is
  untouched by any of that, which is the entire point.

## Deploy

Prerequisites: the Azure CLI, and `func` (Azure Functions Core Tools v4).

```bash
RG=rg-byd-app                      # the resource group you already have
APP=dashcast-relay                 # must be globally unique
STORAGE=stdashcastrelay            # 3-24 chars, lowercase letters and digits only

az group create -n $RG -l westeurope        # skip if it exists
az storage account create -n $STORAGE -g $RG -l westeurope --sku Standard_LRS
az functionapp create -g $RG -n $APP \
    --storage-account $STORAGE \
    --consumption-plan-location westeurope \
    --runtime node --runtime-version 20 --functions-version 4

# The secret lives here and only here.
az functionapp config appsettings set -g $RG -n $APP --settings \
    TELEGRAM_BOT_TOKEN="<the bot token>" \
    TELEGRAM_CHAT_ID="-1004472712700" \
    TELEGRAM_THREAD_BUG="2" \
    TELEGRAM_THREAD_HUD="4"

# Cap the damage an abused endpoint can do.
az functionapp update -g $RG -n $APP --set dailyMemoryTimeQuota=50000

cd relay && npm install && func azure functionapp publish $APP
```

The publish prints the URL. It looks like
`https://dashcast-relay.azurewebsites.net/api/report`.

## Then, in the app

Paste that URL into `RelayUploader.DEFAULT_URL` and ship. Until it is filled in, `isConfigured()` is
false, `TelegramBugReporter` takes its existing direct path, and nothing changes — the relay is
additive by construction.

To try a deployment without a build, put it on a device instead:

```
relay.url=https://dashcast-relay.azurewebsites.net/api/report
```

in `dashcast_channel.properties`, in `Download`. A device value wins over the constant.

## Check it before shipping it

```bash
head -c 200 /dev/urandom > /tmp/probe.bin
curl -sS -X POST "https://<app>.azurewebsites.net/api/report" \
  -H 'Content-Type: application/octet-stream' \
  -H 'X-DashCast-Topic: bug' \
  -H 'X-DashCast-Filename: relay_probe.bin' \
  -H "X-DashCast-Caption: $(printf 'relay probe' | base64 -w0)" \
  --data-binary @/tmp/probe.bin
```

`{"ok":true}` and a file in the group's bug topic. If it answers `503 relay not configured`, the app
settings did not take — check them and restart the app.

## Contract

```
POST <url>
Content-Type: application/octet-stream
X-DashCast-Topic:    bug | hud
X-DashCast-Filename: report file name, [A-Za-z0-9][A-Za-z0-9._-]{0,119}
X-DashCast-Caption:  base64(utf8(caption))    — base64 because a caption has newlines
body:                the report bytes, 64 B .. 45 MB

200 {"ok":true} · 400 bad request · 413 too large · 502 Telegram refused · 503 relay unconfigured
```

`503` is deliberately distinct from `400`: a misconfigured relay must not look like a rejected
report, or the car falls back to a local save and the tester is told their report was refused.

## What it does not do

It does not authenticate the car, and it cannot: there is nothing to authenticate with that would
not be a secret in the APK. It does not deduplicate, retry, or store anything — a failed forward is
a failure the car falls back from, and the car already knows how to keep a report locally.

It does not redact. That happens in the car, before the upload, in `Redactor` — which is where it
belongs, because a report should not leave the vehicle carrying what it must not carry.
