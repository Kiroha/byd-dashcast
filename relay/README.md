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

Prerequisites: the Azure CLI. Core Tools are not needed — `az functionapp deployment source
config-zip` publishes a plain zip, which is how this one was deployed.

**Use Node 22.** `az functionapp list-runtimes` advertises Node 24 for Linux Functions v4 and
`az functionapp create` accepts it, but in France Central Consumption the host never starts: the
site answers 503 on every route, the SCM site answers 503 as well, and Application Insights
receives no telemetry at all because nothing ever ran. There is no error message anywhere — the
only way to find it is to create a second app on Node 22 and watch it come up immediately. Node 20
is refused outright as end-of-life.

```bash
RG=rg-byd-app                      # the resource group you already have
APP=func-dc-relay-bf8097           # must be globally unique
STORAGE=stdcrelaybf8097            # 3-24 chars, lowercase letters and digits only

az group create -n $RG -l francecentral     # skip if it exists
az storage account create -n $STORAGE -g $RG -l francecentral --sku Standard_LRS \
    --min-tls-version TLS1_2 --allow-blob-public-access false
az functionapp create -g $RG -n $APP \
    --storage-account $STORAGE \
    --consumption-plan-location francecentral \
    --runtime node --runtime-version 22 --functions-version 4

# The secret lives here and only here.
az functionapp config appsettings set -g $RG -n $APP --settings \
    TELEGRAM_BOT_TOKEN="<the bot token>" \
    TELEGRAM_CHAT_ID="-100999999999" \
    TELEGRAM_THREAD_BUG="2" \
    TELEGRAM_THREAD_HUD="4"

# Cap the damage an abused endpoint can do.
az functionapp update -g $RG -n $APP --set dailyMemoryTimeQuota=50000

# Harden. None of this is optional on a corporate tenant.
az functionapp config set -g $RG -n $APP --min-tls-version 1.2 --ftps-state Disabled
az functionapp update -g $RG -n $APP --set httpsOnly=true
SITE=$(az functionapp show -g $RG -n $APP --query id -o tsv)
for pol in scm ftp; do
  az resource update --ids "$SITE/basicPublishingCredentialsPolicies/$pol" --set properties.allow=false
done

cd relay && npm install --omit=dev
zip -qr /tmp/relay.zip host.json package.json src node_modules
az functionapp deployment source config-zip -g $RG -n $APP --src /tmp/relay.zip
```

Publishing over AAD still works with basic auth disabled — verified after the fact, not assumed.

The deployed endpoint is
`https://func-dc-relay-bf8097.azurewebsites.net/api/report`.

## Then, in the app

The URL is already in `RelayUploader.DEFAULT_URL`.

To try a deployment without a build, put it on a device instead:

```
relay.url=https://<another-deployment>.azurewebsites.net/api/report
```

in `dashcast_channel.properties`, in `Download`. A device value wins over the constant.

## Check it before shipping it

```bash
head -c 200 /dev/urandom > /tmp/probe.bin
curl -sS -X POST "https://func-dc-relay-bf8097.azurewebsites.net/api/report" \
  -H 'Content-Type: application/octet-stream' \
  -H 'X-DashCast-Topic: bug' \
  -H 'X-DashCast-Filename: relay_probe.bin' \
  -H "X-DashCast-Caption: $(printf 'relay probe' | base64 -w0)" \
  --data-binary @/tmp/probe.bin
```

`{"ok":true}` and a file in the group's bug topic. `503 relay not configured` means
`TELEGRAM_BOT_TOKEN` is not set — which is the state it ships in, deliberately: the token is meant
to be a freshly rotated one, so the deployment and the rotation are the same event.

Validation is checked before configuration, so these can be verified without a token at all:

| Request | Answer |
|---|---|
| unknown topic | 400 |
| filename with a path separator | 400 |
| empty filename | 400 |
| body under 64 bytes | 400 |
| GET or PUT | 404 |
| a well-formed report, no token set | 503 |

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

## What a compromise of this function could reach

Audited after deployment rather than asserted before it:

- **no managed identity** — the single most important control. The function holds no Azure
  credential, so it has no RBAC anywhere in the subscription. This matters more than usual here:
  the account that deployed it is Owner on the subscription and Owner plus Security Admin on the
  management group, and an identity on this app would have been a bridge toward that.
- **no role assignment** scoped to the site.
- **no VNet integration** — no route to any private network.
- **a dedicated storage account**, not the one holding the reverse-engineering blobs. If the
  function's storage connection ever leaked it would reach its own runtime files and nothing else.
- **basic publishing credentials disabled** on both SCM and FTP, FTP disabled outright, remote
  debugging off, HTTPS only, TLS 1.2 minimum.
- **a daily quota**, so the worst case of an abused public endpoint is the relay going quiet for
  the rest of the day.

## What it does not do

It does not authenticate the car, and it cannot: there is nothing to authenticate with that would
not be a secret in the APK. It does not deduplicate, retry, or store anything — a failed forward is
a failure the car falls back from, and the car already knows how to keep a report locally.

It does not redact. That happens in the car, before the upload, in `Redactor` — which is where it
belongs, because a report should not leave the vehicle carrying what it must not carry.
