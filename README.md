# spring-boot-k8s-gitops-flux-sops

A minimal Java 21/Spring Boot service used to show how Kubernetes, GitOps, Flux and SOPS fit together.

The application has two meaningful settings:

```yaml
demo:
  token1: ...
  token2: ...
```

During startup it logs these tokens to prove that the values came from the Kubernetes-mounted configuration file.

This is intentionally wrong for production. Real applications must not print secrets. Here the log line is a workshop probe.

## References

* [Spring Boot documentation](https://docs.spring.io/spring-boot/)
* [Flux Kustomization documentation](https://fluxcd.io/flux/components/kustomize/kustomizations/)
* [Flux SOPS guide](https://fluxcd.io/flux/guides/mozilla-sops/)
* [SOPS project](https://github.com/getsops/sops)
* [age project](https://age-encryption.org/)
* [Kubernetes Secrets documentation](https://kubernetes.io/docs/concepts/configuration/secret/)

## Core idea

* Kubernetes
  * runs the pod
  * stores the runtime Secret
  * mounts the Secret as `/config/application.yml`
  * does not understand SOPS encryption
* Spring Boot
  * starts from the container image
  * loads normal classpath `application.yml`
  * also loads `/config/application.yml` because the Deployment sets `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/config/`
  * binds `demo.token1` and `demo.token2`
* SOPS
  * encrypts secret values before they enter Git
  * leaves Kubernetes metadata readable enough for Kustomize and Flux to process the file
  * uses the workshop age public key for encryption
* Flux
  * watches the GitHub repository
  * reads the Kustomize overlay
  * decrypts `secret.enc.yaml` using the age private key stored in the cluster
  * applies the decrypted Kubernetes Secret and Deployment

The important distinction is that Kubernetes does not decrypt SOPS files. Flux does.

## Why Flux is present

Without Flux, a human or script must decrypt before applying:

```text
Git encrypted secret.enc.yaml
  -> sops -d
  -> plaintext Kubernetes Secret
  -> kubectl apply
  -> pod mount
```

With Flux:

```text
Git encrypted secret.enc.yaml
  -> Flux source-controller fetches Git
  -> Flux kustomize-controller decrypts with SOPS
  -> Flux applies plaintext Secret to the Kubernetes API
  -> kubelet mounts the Secret into the pod
  -> Spring Boot reads /config/application.yml
```

That second path is the GitOps model: the cluster reconciles Git state instead of relying on a local operator command.

## Project layout

```text
src/main/java/                         Spring Boot application
src/main/resources/application.yml     local fallback config
src/test/java/                         Docker Desktop Kubernetes + Flux test
gitops/base/                           shared Kubernetes Deployment and Service
gitops/overlays/dev/                   dev Namespace, patch and SOPS Secret
gitops/overlays/prod/                  prod Namespace, patch and SOPS Secret
gitops/clusters/dev/                   dev Flux wiring
gitops/clusters/prod/                  prod Flux wiring
gitops/age/                            workshop age keypair
scripts/                               key generation and SOPS encryption helpers
```

## Secret handling model

The encrypted Secret is shaped like this after SOPS encryption:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: spring-boot-k8s-gitops-flux-sops-config
type: Opaque
stringData:
  application.yml: ENC[...]
sops:
  ...
```

After Flux decrypts and applies it, Kubernetes mounts the Secret key as this file:

```text
/config/application.yml
```

The decrypted file content is:

```yaml
demo:
  token1: dev-workshop-token1
  token2: dev-workshop-token2
```

or:

```yaml
demo:
  token1: prod-workshop-token1
  token2: prod-workshop-token2
```

depending on the overlay.

## Workshop age key

This project intentionally commits the age private key under:

```text
gitops/age/age-key.txt
```

That is only acceptable because this repository is a workshop/demo. In a real repository:

* commit only the public key
* store the private key outside Git
* inject the private key into the cluster through a secure bootstrap path
* restrict who can decrypt production secrets

## How Flux gets the age private key

Flux does not read `gitops/age/age-key.txt` directly from the Git repository during normal reconciliation. Flux reads a Kubernetes Secret from its own namespace.

The Flux Kustomizations in this project reference:

```yaml
decryption:
  provider: sops
  secretRef:
    name: k8s-plain-secrets
```

That means Flux expects this Secret to exist:

```text
namespace: flux-system
name: k8s-plain-secrets
key: identity.agekey
```

The `k8s-plain-secrets` Secret itself is not SOPS-encrypted in this workshop. It is bootstrap material. When `kubectl apply` sends a Secret with `stringData`, the Kubernetes API server converts that plaintext value into the Secret's `data` field. That conversion is only base64 encoding, not encryption.

So there is no chicken-and-egg problem in this project:

```text
kubectl applies plaintext bootstrap Secret
  -> Kubernetes stores flux-system/k8s-plain-secrets
  -> Flux reads flux-system/k8s-plain-secrets
  -> Flux decrypts SOPS-encrypted application Secrets
```

There would be a chicken-and-egg problem if `flux-sops-age-key-bootstrap.yaml` itself were SOPS-encrypted and Flux needed the same Secret to decrypt it.

In this workshop project, the Secret is committed as normal cluster bootstrap YAML:

```text
gitops/age/age-key.txt
  -> copied into gitops/clusters/dev/flux-sops-age-key-bootstrap.yaml
  -> copied into gitops/clusters/prod/flux-sops-age-key-bootstrap.yaml
  -> kubectl apply -k gitops/clusters/dev
  -> kubectl apply -k gitops/clusters/prod
  -> Kubernetes stores flux-system/k8s-plain-secrets
  -> Flux decrypts gitops/overlays/dev/secret.enc.yaml
  -> Flux decrypts gitops/overlays/prod/secret.enc.yaml
```

This is intentionally simple and intentionally insecure. It keeps the whole workshop self-contained. The important conceptual point is that Flux receives an ordinary Kubernetes Secret named `k8s-plain-secrets`; the source of that Secret is a bootstrap decision.

In a real system, `age-key.txt` should not be committed. Common delivery options are:

* create `flux-system/k8s-plain-secrets` once during cluster bootstrap from an operator machine
* inject it from a cloud secret manager, for example AWS Secrets Manager, Azure Key Vault, Google Secret Manager or HashiCorp Vault
* let the platform bootstrap process create it before Flux starts reconciling application manifests
* use separate age keys per environment or cluster, so dev cannot decrypt prod secrets

The key point is ownership: Git contains encrypted application secrets, while the cluster receives the SOPS decryption identity through a separate trusted bootstrap channel.

Encrypt the dev/prod workshop secrets:

```bash
scripts/encrypt-workshop-secrets.sh
```

The encryption script mutates these files in place:

```text
gitops/overlays/dev/secret.enc.yaml
gitops/overlays/prod/secret.enc.yaml
```

Commit and push the generated key, SOPS config, and encrypted files before running the full test.

## Docker Desktop test model

`./gradlew test` is intentionally an integration test.

It assumes:

* Docker Desktop is running
* Docker Desktop Kubernetes is enabled
* current Kubernetes context is `docker-desktop`
* `kubectl` is installed
* `flux` CLI is installed
* `sops` and `age-keygen` were used to generate real encrypted secrets
* this repository has been pushed to `https://github.com/mtumilowicz/spring-boot-k8s-gitops-flux-sops.git` on branch `main`

Verify the required Kubernetes tools:

```bash
kubectl version --client
kubectl config current-context
flux --version
```

Expected Kubernetes context:

```text
docker-desktop
```

The test:

* builds the local image `spring-boot-k8s-gitops-flux-sops:latest`
* installs Flux into Docker Desktop Kubernetes if `flux-system` is missing
* applies the Flux SOPS age Secret, sources and Kustomizations from `gitops/clusters/dev` and `gitops/clusters/prod`
* waits for Flux to reconcile dev and prod
* waits for the Kubernetes Deployments
* reads pod logs
* verifies:
  * dev logs `demo.token1=dev-workshop-token1` and `demo.token2=dev-workshop-token2`
  * prod logs `demo.token1=prod-workshop-token1` and `demo.token2=prod-workshop-token2`

Run:

```bash
./gradlew test
```

This command mutates the local Docker Desktop cluster. On success, the test deletes the app namespaces and Flux custom resources it owns. It does not uninstall Flux.

## Why the test reads logs

The goal is not to test a REST endpoint. The goal is to verify the configuration supply chain:

```text
encrypted Git file -> Flux SOPS decryption -> Kubernetes Secret -> mounted file -> Spring Boot property
```

The startup log is the smallest observable proof that the tokens reached Spring Boot through that path.
