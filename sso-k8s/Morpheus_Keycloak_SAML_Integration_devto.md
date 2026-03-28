---
title: "Morpheus Enterprise SAML Integration with Keycloak on Kubernetes: A Complete Technical Guide"
published: false
description: "Step-by-step guide to deploying Keycloak on Kubernetes and integrating HPE Morpheus Enterprise with SAML 2.0 SSO, including LDAP federation, role mapping, and troubleshooting."
tags: keycloak, saml, kubernetes, sso
cover_image:
---

## 1. Introduction

Enterprise environments demand centralized identity management. When you operate HPE Morpheus Enterprise as your cloud management platform, integrating it with a dedicated Identity Provider (IdP) via SAML 2.0 eliminates password sprawl and gives you single sign-on (SSO) across the entire infrastructure stack.

In this post, we walk through the entire journey: understanding how Morpheus handles SAML under the hood, deploying Keycloak on Kubernetes as your IdP, wiring the two together, and diagnosing issues when things do not go as planned. Every configuration value and YAML snippet comes from a real lab deployment, so you can replicate it in your own environment.

**Lab environment:** Kubernetes (single-node / Minikube compatible), Keycloak 26.x, Morpheus Enterprise 8.x, Active Directory for user federation.

---

## 2. Morpheus SAML Architecture

HPE Morpheus Enterprise supports SAML 2.0 as an Identity Source type within its Administration panel. Understanding how Morpheus participates in the SAML exchange is essential before configuring anything on the Keycloak side.

### 2.1 Morpheus as a SAML Service Provider (SP)

Morpheus acts exclusively as a **SAML Service Provider**. It does not function as an Identity Provider itself. When a user attempts to log in via SSO, Morpheus generates a SAML AuthnRequest, redirects the browser to the configured IdP, and then consumes the SAML Response (assertion) returned by the IdP.

### 2.2 Key SAML Endpoints in Morpheus

When you create a SAML Identity Source in Morpheus, the platform automatically generates two critical values:

| Endpoint | Description |
|----------|-------------|
| **SP Entity ID** | A unique identifier for Morpheus as a Service Provider. Auto-generated from the hostname, e.g. `https://morphsrv01.hpetrlab.local/saml/<uniqueID>` |
| **SP ACS URL** | The callback URL where the IdP posts the SAML Response after authentication, e.g. `https://morphsrv01.hpetrlab.local/externalLogin/callback/<uniqueID>` |
| **Login Redirect URL** | The IdP's SAML SSO endpoint where Morpheus sends the AuthnRequest |
| **SAML Logout Redirect URL** | The IdP's SAML SLO endpoint for single logout |

> **Note:** The SP Entity ID and ACS URL are generated only after you save the Identity Source for the first time. You must save first, then copy these values to configure the IdP.

### 2.3 SAML Request and Response Configuration

Morpheus provides granular control over how SAML requests are signed and responses are validated:

| Setting | Options & Description |
|---------|----------------------|
| **SAML Request** | No Signature / Self Signed / Custom RSA Signature — Controls whether AuthnRequest messages are signed |
| **SAML Response** | Do Not Validate / Validate Assertion Signature — Controls signature validation on the IdP's assertion |
| **POST Binding Mode** | ON/OFF — Uses HTTP-POST binding instead of HTTP-Redirect |
| **Includes SAML Request Parameter** | Yes/No — Whether the SAML request is included in the redirect |

### 2.4 Assertion Attribute Mappings

Morpheus maps SAML assertion attributes to internal user fields:

| Morpheus Field | Expected SAML Attribute |
|----------------|------------------------|
| Given Name | `firstName` |
| Surname | `lastName` |
| Email | `email` (or NameID) |

### 2.5 Role Mapping Mechanism

Morpheus supports role-based access control through SAML group assertions:

| Role Mapping Field | Description |
|--------------------|-------------|
| **Default Role** | The role assigned to all authenticated users (e.g., Standard User) |
| **Role Attribute Name** | The SAML attribute containing group/role info (e.g., `groups`) |
| **Required Role Attribute Value** | A group name the user must belong to for authorization (e.g., `mspusers`) |

<!--
📸 IMAGE PLACEHOLDER: Morpheus Administration > Identity Sources > SAML SSO configuration page
👉 Word dosyasından bu ekran görüntüsünü buraya ekleyin
-->

---

## 3. Keycloak-SAML Ecosystem: How It Works

Keycloak is an open-source Identity and Access Management (IAM) solution maintained by the CNCF. It supports OpenID Connect, OAuth 2.0, and SAML 2.0 protocols natively. In our setup, Keycloak serves as the SAML Identity Provider (IdP) that authenticates users against an Active Directory backend via LDAP federation.

### 3.1 Core Keycloak Concepts

| Concept | Description |
|---------|-------------|
| **Realm** | A tenant-level isolation boundary. Each realm has its own users, clients, roles, and identity providers. Our realm: `morpheus-lab` |
| **Client** | An application that delegates authentication to Keycloak. Morpheus is registered as a SAML client |
| **User Federation** | Allows Keycloak to pull users from LDAP/Active Directory without duplicating credentials |
| **Protocol Mappers** | Transform user attributes and group memberships into SAML assertions |
| **Roles & Groups** | Realm-level and client-level roles. AD groups can be synced and mapped into assertions |

### 3.2 SAML 2.0 Authentication Flow (SP-Initiated SSO)

The diagram below illustrates the SP-Initiated SAML SSO flow between Morpheus and Keycloak:

<!--
📸 IMAGE PLACEHOLDER: SAML 2.0 SP-Initiated SSO Flow Diagram
👉 Önerilen referans görseller:
   - https://developer.okta.com/docs/concepts/saml/ (Okta SAML flow diagram)
   - https://en.wikipedia.org/wiki/SAML_2.0 (Wikipedia SAML sequence diagram)
   - Kendi çizdiğiniz bir diagram (draw.io / Mermaid)
-->

```
┌──────────┐         ┌──────────┐         ┌──────────────┐
│  Browser │         │ Morpheus │         │   Keycloak   │
│  (User)  │         │   (SP)   │         │    (IdP)     │
└────┬─────┘         └────┬─────┘         └──────┬───────┘
     │  1. Access App     │                      │
     │───────────────────>│                      │
     │                    │  2. SAML AuthnRequest │
     │                    │─────────────────────>│
     │  3. Redirect to    │                      │
     │<───────────────────│                      │
     │     Keycloak       │                      │
     │  4. Login Form     │                      │
     │<──────────────────────────────────────────│
     │  5. Credentials    │                      │
     │──────────────────────────────────────────>│
     │                    │  6. Validate against  │
     │                    │     Active Directory  │
     │                    │                      │──┐
     │                    │                      │  │ LDAP Bind
     │                    │                      │<─┘
     │                    │  7. SAML Response     │
     │<──────────────────────────────────────────│
     │  8. POST to ACS    │                      │
     │───────────────────>│                      │
     │  9. Session Created│                      │
     │<───────────────────│                      │
     │  10. Access Granted│                      │
     │<───────────────────│                      │
```

**Step-by-step:**

1. The user navigates to the Morpheus login page and clicks the SSO login button.
2. Morpheus generates a SAML AuthnRequest and redirects the user's browser to Keycloak's SAML endpoint: `https://192.168.41.101:30443/realms/morpheus-lab/protocol/saml`
3. Keycloak presents the login form. The user enters their AD credentials.
4. Keycloak authenticates the user against the federated LDAP/AD backend.
5. On successful authentication, Keycloak constructs a **SAML Response** containing signed assertions with user attributes (`firstName`, `lastName`) and group memberships (`groups`).
6. Keycloak POSTs the SAML Response to the Morpheus ACS URL.
7. Morpheus validates the assertion signature, maps attributes and roles, creates or updates the user session, and grants access.

### 3.3 Authentication vs Authorization

#### Authentication (Who are you?)

- **Keycloak acts as the authentication broker**, sitting between the application (Morpheus) and the identity store (Active Directory).
- **User Federation (LDAP)** allows Keycloak to verify credentials against AD without storing passwords locally. Keycloak performs LDAP BIND operations to authenticate users.
- **MFA** can be layered on top through Keycloak's authentication flow configuration.
- **Session Management:** Once authenticated, Keycloak creates a session. Subsequent SAML requests within the session's lifetime do not require re-authentication (SSO behavior).

<!--
📸 IMAGE PLACEHOLDER: Keycloak authentication flow diagram
👉 Önerilen referans: https://www.thomasvitale.com/keycloak-authentication-flow-sso-client/
-->

#### Authorization (What can you do?)

- Keycloak syncs AD groups via the **LDAP Group Mapper** (`mspusers`, `apparchitech`, `selfservice`).
- The **SAML Group List Mapper** serializes group memberships into the SAML assertion as a `groups` attribute.
- Morpheus reads the `groups` attribute and maps it to internal roles:
  - `mspusers` → authorized user (Required Role)
  - `apparchitech` → Application Architect role
  - `selfservice` → Self-Service User role
- Users not in the required group are **denied access** even if authentication succeeds.

### 3.4 Single Logout (SLO) Flow

```
User clicks Logout → Morpheus sends LogoutRequest → Keycloak terminates session
→ Keycloak POSTs LogoutResponse to /login/auth → User lands on login page
```

> **Warning:** The Logout Service POST Binding URL must be set to `/login/auth` (the login page), NOT the ACS callback URL. Morpheus's ACS handler cannot process LogoutResponse objects and throws a `GroovyCastException`.

---

## 4. Deploying Keycloak on Kubernetes

This section walks through deploying a production-grade Keycloak cluster on Kubernetes using a single YAML manifest.

### 4.1 Architecture Overview

The deployment stack:

- **Keycloak StatefulSet** (2 replicas) with Infinispan clustering for HA
- **PostgreSQL Deployment** with PersistentVolumeClaim (10Gi)
- **Kubernetes Secret** for admin and database passwords
- **Self-signed TLS certificates** generated by init containers
- **NodePort Service** for external HTTPS access on port 30443
- **Headless Service** for Infinispan/JGroups cluster discovery

### 4.2 Prerequisites

- A running Kubernetes cluster or Minikube instance
- `kubectl` configured and connected
- At least 4GB RAM and 2 CPU cores available
- Storage provisioner available (default StorageClass or Rook-Ceph)

### 4.3 Step 1: Prepare Secrets

The YAML uses a Kubernetes Secret to store sensitive credentials. The passwords are Base64-encoded:

```bash
# Encode your passwords
echo -n 'YourAdminPassword!' | base64
# Output: WW91ckFkbWluUGFzc3dvcmQh

echo -n 'YourDBPassword!' | base64
# Output: WW91ckRCUGFzc3dvcmQh
```

The Secret resource in the YAML:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: keycloak-secret
  namespace: keycloak
type: Opaque
data:
  admin-password: <base64-encoded-admin-password>
  db-password: <base64-encoded-db-password>
```

> **Note:** Never commit plain-text passwords to version control. Use a secrets manager (Vault, Sealed Secrets) in production.

### 4.4 Step 2: Namespace and Storage

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: keycloak
  labels:
    app.kubernetes.io/part-of: sso-stack
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: postgres-pvc
  namespace: keycloak
spec:
  accessModes: [ReadWriteOnce]
  resources:
    requests:
      storage: 10Gi
```

### 4.5 Step 3: PostgreSQL Database

Keycloak requires an external database in production mode. Key points from our manifest:

```yaml
containers:
  - name: postgres
    image: postgres:17
    env:
      - name: POSTGRES_USER
        value: keycloak
      - name: POSTGRES_PASSWORD
        valueFrom:
          secretKeyRef:
            name: keycloak-secret
            key: db-password
      - name: POSTGRES_DB
        value: keycloak
      - name: PGDATA
        value: /var/lib/postgresql/data/pgdata
    readinessProbe:
      exec:
        command: ["pg_isready", "-U", "keycloak"]
      initialDelaySeconds: 10
      periodSeconds: 5
    resources:
      requests:
        memory: 256Mi
        cpu: 100m
      limits:
        memory: 512Mi
        cpu: 500m
```

### 4.6 Step 4: Keycloak StatefulSet

The Keycloak deployment uses a **StatefulSet with 2 replicas** for high availability.

**Init Containers:**

1. **wait-for-postgres:** Polls PostgreSQL port 5432 before Keycloak starts
2. **generate-tls-cert:** Generates a self-signed TLS certificate (10 years validity)

```yaml
initContainers:
  - name: generate-tls-cert
    image: alpine:3.19
    command: ["sh", "-c"]
    args:
      - |
        apk add --no-cache openssl
        openssl req -x509 -nodes -days 3650 \
          -newkey rsa:2048 \
          -keyout /certs/tls.key \
          -out /certs/tls.crt \
          -subj "/CN=keycloak-morpheus-lab/O=Lab/C=TR"
```

**Key Environment Variables:**

| Variable | Purpose |
|----------|---------|
| `KC_BOOTSTRAP_ADMIN_USERNAME` | Initial admin username (`admin`) |
| `KC_BOOTSTRAP_ADMIN_PASSWORD` | Admin password from Secret |
| `KC_DB` / `KC_DB_URL_HOST` | Database type and host (`postgres`) |
| `KC_HTTPS_CERTIFICATE_FILE` | Path to TLS certificate |
| `KC_HTTPS_CERTIFICATE_KEY_FILE` | Path to TLS key |
| `KC_CACHE` | Clustering mode (`ispn` = Infinispan) |
| `KC_HOSTNAME_STRICT` | Disabled for NodePort/self-signed setups |
| `KC_FEATURES` | Additional features (`token-exchange`) |
| `KC_HEALTH_ENABLED` | Enables `/health/*` endpoints for probes |

**Health Probes:**

- **startupProbe:** `/health/started` — 1s interval, 600 retries (10-minute startup window)
- **readinessProbe:** `/health/ready` — Every 10s, 3 failures to mark unready
- **livenessProbe:** `/health/live` — Every 10s, 3 failures to restart pod

### 4.7 Step 5: Services

| Service | Type & Purpose |
|---------|----------------|
| `keycloak` (ClusterIP) | Internal access: ports 8080 (HTTP) and 8443 (HTTPS) |
| `keycloak-discovery` (Headless) | JGroups cluster member discovery on port 7800 |
| `keycloak-nodeport` (NodePort) | External access: port **30080** (HTTP) and **30443** (HTTPS) |

### 4.8 Step 6: Deploy

```bash
# Apply the complete manifest
kubectl apply -f keycloak.yaml

# Watch the rollout
kubectl rollout status statefulset/keycloak -n keycloak --timeout=10m

# Verify all pods are running
kubectl get pods -n keycloak

# Access the admin console
# https://<node-ip>:30443/admin
```

> **Tip:** For Minikube, use `minikube ip` to get the node IP. For a multi-node cluster, any node's IP will work with NodePort.

<!--
📸 IMAGE PLACEHOLDER: Terminal output of kubectl get pods -n keycloak showing all pods running
-->

---

## 5. Keycloak-Morpheus SAML Integration Guide

With Keycloak deployed and running, we can now configure the SAML integration.

### 5.1 Step 1: Create the Keycloak Realm

1. Log in to Keycloak Admin Console at `https://192.168.41.101:30443/admin`
2. Click the realm dropdown (top-left) and select "Create Realm"
3. Set Realm Name to: `morpheus-lab`
4. Click Create

<!--
📸 IMAGE PLACEHOLDER: Keycloak Admin Console > Create Realm dialog
👉 Word dosyasından bu ekran görüntüsünü buraya ekleyin
-->

### 5.2 Step 2: Configure LDAP User Federation

Navigate to **morpheus-lab > User Federation > Add provider > LDAP** and configure:

| Setting | Value |
|---------|-------|
| Name | `LAB AD` |
| Vendor | `Active Directory` |
| Connection URL | `ldap://msdc01.hpetrlab.local:389` |
| Bind Type | `simple` |
| Bind DN | `CN=svc-keycloak,CN=Users,DC=hpetrlab,DC=local` |
| Users DN | `CN=Users,DC=hpetrlab,DC=local` |
| Username LDAP Attribute | `sAMAccountName` |
| Edit Mode | `READ_ONLY` |
| Import Users | `ON` |

**Add Group Mapper** (Mappers > Add mapper):

| Setting | Value |
|---------|-------|
| Mapper Type | `group-ldap-mapper` |
| Groups DN | `CN=Users,DC=hpetrlab,DC=local` |
| Group Name LDAP Attribute | `cn` |
| Membership LDAP Attribute | `member` |
| Mode | `READ_ONLY` |
| User Roles Retrieve Strategy | `LOAD_GROUPS_BY_MEMBER_ATTRIBUTE` |

> **Note:** After saving, click **'Sync all users'** and **'Sync LDAP groups to Keycloak'** to import users and groups from Active Directory.

<!--
📸 IMAGE PLACEHOLDER: Keycloak > User Federation > LAB AD settings
-->

### 5.3 Step 3: Create the SAML Client for Morpheus

1. Navigate to **morpheus-lab > Clients > Create Client**
2. Set Client Type to **SAML**
3. Set Client ID (SP Entity ID) to: `https://morphsrv01.hpetrlab.local/saml/N3hbmK5BO`
4. Set Name to: `Morpheus Enterprise`

**Access Settings:**

| Setting | Value |
|---------|-------|
| Root URL | `https://morphsrv01.hpetrlab.local` |
| Valid Redirect URIs (ACS URL) | `https://morphsrv01.hpetrlab.local/externalLogin/callback/N3hbmK5BO` |
| Master SAML Processing URL | `https://morphsrv01.hpetrlab.local/externalLogin/callback/N3hbmK5BO` |
| IDP-Initiated SSO URL Name | `morpheus` |

**SAML Capabilities:**

| Setting | Value |
|---------|-------|
| Name ID Format | `username` |
| Force POST Binding | `ON` |
| Include AuthnStatement | `ON` |

**Signature & Encryption:**

| Setting | Value |
|---------|-------|
| Sign Documents | `ON` |
| Sign Assertions | `ON` |
| Signature Algorithm | `RSA_SHA256` |
| **Client Signature Required** | **`OFF` (CRITICAL!)** |
| Encrypt Assertions | `OFF` |

> **Warning:** `Client Signature Required` must be **OFF**. Morpheus signs requests with a self-signed certificate that does not match the certificate registered in Keycloak. If this is ON, every SAML request from Morpheus will be rejected.

<!--
📸 IMAGE PLACEHOLDER: Keycloak > Clients > Morpheus Enterprise > Settings
-->

### 5.4 Step 4: Configure Logout (Advanced Settings)

Navigate to **Advanced tab > Fine Grain SAML Endpoint Configuration:**

| Setting | Value |
|---------|-------|
| **Logout Service POST Binding URL** | `https://morphsrv01.hpetrlab.local/login/auth` |

> **This is crucial!** Setting this URL to `/login/auth` prevents the `GroovyCastException` bug. The Morpheus ACS callback handler cannot process SAML LogoutResponse objects.

### 5.5 Step 5: Configure SAML Mappers

Navigate to **Client Scopes > dedicated > Mappers** and add:

**Mapper 1: Groups (Group List)**

| Setting | Value |
|---------|-------|
| Mapper Type | `Group list` |
| SAML Attribute Name | `groups` |
| SAML Attribute NameFormat | `Basic` |
| Single Group Attribute | `OFF` |
| Full Group Path | `OFF` |

**Mapper 2: firstName (User Attribute)**

| Setting | Value |
|---------|-------|
| Mapper Type | `User Attribute` |
| User Attribute | `firstName` |
| SAML Attribute Name | `firstName` |

**Mapper 3: lastName (User Attribute)**

| Setting | Value |
|---------|-------|
| Mapper Type | `User Attribute` |
| User Attribute | `lastName` |
| SAML Attribute Name | `lastName` |

<!--
📸 IMAGE PLACEHOLDER: Keycloak > Client Scopes > dedicated > Mappers list
-->

### 5.6 Step 6: Copy the Realm Certificate

1. Navigate to **morpheus-lab > Realm Settings > Keys**
2. Find the **RS256** key row and click the **Certificate** button
3. Copy the entire certificate string

<!--
📸 IMAGE PLACEHOLDER: Keycloak > Realm Settings > Keys > RS256 certificate
-->

### 5.7 Step 7: Configure Morpheus Identity Source

Navigate to **Administration > Identity Sources > Add Identity Source**:

| Setting | Value |
|---------|-------|
| Type | `SAML SSO` |
| Name | `Keycloak-SSO` |
| Login Redirect URL | `https://192.168.41.101:30443/realms/morpheus-lab/protocol/saml` |
| SAML Logout Redirect URL | `https://192.168.41.101:30443/realms/morpheus-lab/protocol/saml` |
| Includes SAML Request Parameter | `Yes` |
| POST Binding Mode | `ON` |
| SAML Request | `Self Signed` |
| SAML Response | `Validate Assertion Signature` |
| SAML Response Public Key | *(paste RS256 certificate from Keycloak)* |

**Assertion Attribute Mappings:**

| Morpheus Field | Value |
|----------------|-------|
| Given Name Attribute Name | `firstName` |
| Surname Attribute Name | `lastName` |

**Role Mappings:**

| Morpheus Field | Value |
|----------------|-------|
| Default Role | `Standard User` |
| Role Attribute Name | `groups` |
| Required Role Attribute Value | `mspusers` |
| Application Architect Role | `apparchitech` |
| Self Service User Role | `selfservice` |

Click **Save Changes**.

<!--
📸 IMAGE PLACEHOLDER: Morpheus > Identity Sources > Keycloak-SSO configuration
📸 IMAGE PLACEHOLDER: Morpheus > Identity Sources > Role Mappings
-->

### 5.8 Step 8: Test the Integration

1. Open a new browser / incognito window
2. Navigate to `https://morphsrv01.hpetrlab.local`
3. Click the SSO login option (Keycloak-SSO should appear)
4. You will be redirected to Keycloak's login page
5. Enter an AD username (e.g., `emre.baykal`) and password
6. On success, you will be redirected back to Morpheus and logged in

<!--
📸 IMAGE PLACEHOLDER: Morpheus login page with SSO button
📸 IMAGE PLACEHOLDER: Keycloak login form during SSO redirect
📸 IMAGE PLACEHOLDER: Morpheus dashboard after successful SSO login
-->

---

## 6. Troubleshooting SAML Integration

SAML integrations can fail silently or produce cryptic errors. This section covers the most common issues.

### 6.1 Diagnostic Tools

| Tool | Usage |
|------|-------|
| **SAML Tracer** (Browser Extension) | Captures SAML requests/responses in real-time. Available for Firefox and Chrome |
| **Keycloak Events** | Enable in Realm Settings > Events. Shows auth attempts and errors |
| **Keycloak Logs** | `kubectl logs statefulset/keycloak -n keycloak -f` |
| **Morpheus Logs** | `/var/log/morpheus/morpheus-ui/current` |
| **Base64 Decoder** | `echo '<base64>' \| base64 -d \| xmllint --format -` |

### 6.2 Common Issues and Solutions

#### Issue 1: 'Invalid Requester' Error

| | |
|-|-|
| **Symptom** | Keycloak returns 'Invalid requester' or 'Client not found' |
| **Cause** | SP Entity ID in Morpheus doesn't match Client ID in Keycloak |
| **Solution** | Copy the exact SP Entity ID from Morpheus Identity Source and use it as Client ID in Keycloak |

#### Issue 2: Signature Validation Failure

| | |
|-|-|
| **Symptom** | 'Signature validation failed' or 'Invalid signature' |
| **Cause** | SAML Response Public Key in Morpheus doesn't match Keycloak's RS256 certificate |
| **Solution** | Copy fresh certificate from Keycloak > Realm Settings > Keys > RS256 > Certificate. **Must be updated after every Keycloak redeployment** |

#### Issue 3: User Authenticated but Access Denied

| | |
|-|-|
| **Symptom** | User authenticates at Keycloak but gets 'Access Denied' in Morpheus |
| **Cause** | User not in the required group, or Group List mapper missing |
| **Solution** | 1) Verify user is in `mspusers` AD group. 2) Check Group List mapper in client scopes. 3) Use SAML Tracer to verify `groups` attribute in assertion |

#### Issue 4: GroovyCastException on Logout

| | |
|-|-|
| **Symptom** | 500 error on logout with `GroovyCastException` in logs |
| **Cause** | LogoutResponse sent to ACS URL instead of login page |
| **Solution** | Set Logout Service POST Binding URL to `https://morphsrv01.hpetrlab.local/login/auth` in Keycloak Advanced settings |

#### Issue 5: Login Loop / Redirect Loop

| | |
|-|-|
| **Symptom** | Browser keeps redirecting between Morpheus and Keycloak |
| **Cause** | Mixed HTTP/HTTPS, clock skew, or invalid ACS URL |
| **Solution** | 1) Ensure both use HTTPS. 2) Verify NTP sync (SAML assertions are time-sensitive). 3) Check Valid Redirect URIs matches exactly |

#### Issue 6: Attributes Not Mapped

| | |
|-|-|
| **Symptom** | firstName/lastName are empty, roles not assigned |
| **Cause** | SAML mappers missing or attribute names don't match |
| **Solution** | 1) Add User Attribute mappers for `firstName` and `lastName`. 2) Names are **case-sensitive**. 3) Use SAML Tracer to verify attributes in assertion XML |

#### Issue 7: LDAP Users Not Appearing

| | |
|-|-|
| **Symptom** | No users appear after LDAP federation setup |
| **Cause** | Wrong Bind DN, Users DN, or network connectivity |
| **Solution** | 1) Test connectivity from pod. 2) Verify Bind DN has read access. 3) Click 'Sync all users'. 4) Check Keycloak logs for LDAP errors |

### 6.3 SAML Assertion Debugging Checklist

When debugging, use SAML Tracer to capture the assertion and verify:

- **NameID:** Present and in expected format (`username`)?
- **Issuer:** Matches the Keycloak realm URL?
- **AudienceRestriction:** Audience matches Morpheus SP Entity ID?
- **Conditions/NotBefore/NotOnOrAfter:** Valid timestamps? Check for clock skew
- **AuthnStatement:** Present? (Required by Morpheus)
- **AttributeStatement:** `firstName`, `lastName`, `groups` attributes present with correct values?
- **Signature:** Assertion signed? Certificate matches?

### 6.4 Useful Debug Commands

```bash
# Check Keycloak pod logs
kubectl logs -f statefulset/keycloak -n keycloak

# Decode a SAML Response from browser
echo '<base64-saml-response>' | base64 -d | xmllint --format -

# Test LDAP connectivity from inside the cluster
kubectl exec -it keycloak-0 -n keycloak -- \
  sh -c 'nc -zv msdc01.hpetrlab.local 389'

# Check Keycloak health
kubectl exec -it keycloak-0 -n keycloak -- \
  curl -sk https://localhost:8443/health/ready
```

---

## 7. Conclusion

Integrating Morpheus Enterprise with Keycloak via SAML provides a robust, centralized authentication solution for enterprise cloud management. Key takeaways:

- **Morpheus acts as a SAML SP;** Keycloak acts as the SAML IdP with AD backend
- The Kubernetes deployment uses a **StatefulSet with Infinispan clustering** for HA
- **Self-signed TLS certificates** are generated by init containers — no external cert-manager required for lab environments
- `Client Signature Required` must be **OFF** in Keycloak for Morpheus compatibility
- The **Logout Service POST Binding URL** must point to `/login/auth` to avoid the `GroovyCastException` bug
- Always **update the SAML Response Public Key** in Morpheus after redeploying Keycloak

Have questions or ran into a different issue? Drop a comment below!

---

*Written by Emre Baykal — March 2026*
