# Company certificates

Put the trust store with your company root CA (and, for mutual TLS, your client certificate) here.
`*.p12`, `*.jks`, `*.pem`, `*.crt` and `*.key` in this folder are git-ignored: never commit them.

## Company root CA (fixes "PKIX path building failed")

Ask IT for the root CA (`.crt`/`.pem`), or export it from your browser (padlock → certificate → root → export). Then:

```bash
keytool -importcert -noprompt -alias company-root -file company-root.crt \
        -keystore config/certs/company-truststore.p12 -storetype PKCS12 -storepass "$TRUSTSTORE_PASSWORD"
```

and in `config/env/<env>.properties`:

```properties
tls.trustStore=config/certs/company-truststore.p12
tls.trustStorePassword=${TRUSTSTORE_PASSWORD}
```

This store replaces Java's default list for the API calls, so import every CA the API certificates chain to
(public CAs too if some APIs use them). Check with `java -jar target/api-test-platform.jar doctor --env <env>`.

Maven downloads use the JDK's own trust store. If Maven also fails with PKIX, import the CA there once:
`keytool -importcert -cacerts -alias company-root -file company-root.crt` (default password `changeit`),
or on macOS run Maven with `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=KeychainStore"` to use the login keychain.

## Client certificate (mutual TLS)

```properties
tls.keyStore=config/certs/client.p12
tls.keyStorePassword=${CLIENT_CERT_PASSWORD}
```

From a `.crt` + `.key` pair: `openssl pkcs12 -export -in client.crt -inkey client.key -out config/certs/client.p12`.
