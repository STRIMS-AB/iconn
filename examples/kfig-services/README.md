# Services in kfig's shape

`iconn.yaml` holds services written exactly as a [kfig](https://kfig.dev) file holds them
for one environment. Each is a `type` and either its connection details (real) or `mock`
(mocked). iconn checks every service against its regl schema (module `iconn-services`), the
schema kfig checks it with too.

```java
try (ResourceManager services = Iconn.load("examples/kfig-services/iconn.yaml")) {
    HttpResource fraud = services.get("fraud.check", HttpResource.class);
    fraud.post("/score", "{}");                       // {"riskScore":0.05,"action":"ALLOW"}
}

// The same services, read from kfig for the environment the code runs in
try (ResourceManager services = Iconn.fromKfig("https://kfig.dev/payment_gateway/dev", System.getenv("KFIG_KEY"))) {
    ...
}
```
