# facebed

Facebook embed proxy, now in Java because apparently I enjoy bean cosplay and extra moving parts.

Co-authored by GPT-5.4-mini inside Opencode. The machine gets a chair too.

[![2IrN8Ux.png](https://iili.io/2IrN8Ux.png)](https://freeimage.host/)

## For users

Replace `www.facebook.com` with `facebed.com`.

Discord/Vencord regex:

```text
Find: https://(www.)?facebook.com/(.*)
Replace: https://facebed.com/$2
```

Append `/text` for text-only mode.

## For developers

### Run locally

```bash
mvn spring-boot:run
```

or

```bash
mvn -DskipTests package
java -jar target/facebed-1.0.0-SNAPSHOT.jar
```

### Docker

```bash
docker build -t facebed .
docker run --rm -p 9812:9812 facebed
```

### Config

```yaml
host: 0.0.0.0
port: 9812
timezone: 7
banned_users: []
notifier_webhook: ''
```

`cookies.json` still works. If it expires, the app will complain with great confidence.

## Notes

This repo is a Facebook embed gremlin with a Spring-shaped hat.
The code is intentionally a little overbuilt because that is the joke.

Not affiliated with Meta or Facebook.
