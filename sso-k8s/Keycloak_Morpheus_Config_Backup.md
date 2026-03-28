# Keycloak — Morpheus SSO Configuration Backup
**Date:** 2026-03-27
**Realm:** `morpheus-lab`
**Keycloak:** `https://192.168.41.101:30443`
**Morpheus:** `https://morphsrv01.hpetrlab.local`

> This file serves as a reference for re-applying all Keycloak and Morpheus settings
> from scratch after a redeployment.

---

## 1. Realm

| Field | Value |
|-------|-------|
| Realm Name | `morpheus-lab` |
| Display Name | *(empty)* |
| Frontend URL | *(empty)* |
| User-managed access | OFF |
| Organizations | OFF |

---

## 2. User Federation — Active Directory (LDAP)

### 2.1 General Settings

| Field | Value |
|-------|-------|
| Name | `LAB AD` |
| Vendor | `Active Directory` |
| Connection URL | `ldap://msdc01.hpetrlab.local:389` |
| Enable StartTLS | OFF |
| Connection pooling | ON |
| Bind Type | `simple` |
| Bind DN | `CN=svc-keycloak,CN=Users,DC=hpetrlab,DC=local` |
| Bind Credential | *(AD service account password)* |
| Users DN | `CN=Users,DC=hpetrlab,DC=local` |
| Username LDAP Attribute | `sAMAccountName` |
| RDN LDAP Attribute | `cn` |
| UUID LDAP Attribute | `objectGUID` |
| User Object Classes | `person, organizationalPerson, user` |
| Search Scope | `One Level` |
| Edit Mode | `READ_ONLY` |
| Sync Registrations | OFF |
| Import Users | ON |
| Pagination | ON |
| Remove invalid users during searches | ON |

### 2.2 Group Mapper (`group-mapper`)

| Field | Value |
|-------|-------|
| Mapper Type | `group-ldap-mapper` |
| Groups DN | `CN=Users,DC=hpetrlab,DC=local` |
| Group Name LDAP Attribute | `cn` |
| Group Object Classes | `group` |
| Membership LDAP Attribute | `member` |
| Membership Attribute Type | `DN` |
| Membership User LDAP Attribute | `sAMAccountName` |
| Mode | `READ_ONLY` |
| User Roles Retrieve Strategy | `LOAD_GROUPS_BY_MEMBER_ATTRIBUTE` |
| Preserve Group Inheritance | OFF |
| Ignore Missing Groups | ON |
| Drop non-existing groups during sync | OFF |
| Groups Path | `/` |

---

## 3. SAML Client — Morpheus

**Client UUID:** `fc143993-1bf0-4497-8002-6e6178095e3e`

### 3.1 General / Access Settings

| Field | Value |
|-------|-------|
| Client ID (SP Entity ID) | `https://morphsrv01.hpetrlab.local/saml/N3hbmK5BO` |
| Name | `Morpheus Enterprise` |
| Enabled | ON |
| Root URL | `https://morphsrv01.hpetrlab.local` |
| Home URL / Base URL | *(empty)* |
| **Valid Redirect URIs (ACS URL)** | `https://morphsrv01.hpetrlab.local/externalLogin/callback/N3hbmK5BO` |
| Valid Post Logout Redirect URIs | *(empty)* |
| **Master SAML Processing URL** | `https://morphsrv01.hpetrlab.local/externalLogin/callback/N3hbmK5BO` |
| IDP-Initiated SSO URL Name | `morpheus` |

### 3.2 SAML Capabilities

| Field | Value |
|-------|-------|
| Name ID Format | `username` |
| Force Name ID Format | OFF |
| Force POST Binding | ON |
| Force Artifact Binding | OFF |
| Include AuthnStatement | ON |
| Include OneTimeUse Condition | OFF |
| Optimize REDIRECT signing key lookup | OFF |
| Allow ECP Flow | OFF |

### 3.3 Signature & Encryption

| Field | Value |
|-------|-------|
| Sign Documents | ON |
| Sign Assertions | ON |
| Signature Algorithm | `RSA_SHA256` |
| SAML Signature Key Name | `NONE` |
| Canonicalization Method | `EXCLUSIVE` |
| **Client Signature Required** | **OFF** ← critical |
| Encrypt Assertions | OFF |

### 3.4 Logout Configuration (Advanced → Fine Grain SAML Endpoint Configuration)

| Field | Value |
|-------|-------|
| **Logout Service POST Binding URL** | `https://morphsrv01.hpetrlab.local/login/auth` |
| Logout Service Redirect Binding URL | *(empty)* |

> Setting the Logout Service POST Binding URL to the Morpheus login page prevents
> the `GroovyCastException` bug. Keycloak posts the LogoutResponse to `/login/auth`
> instead of the ACS handler, which cannot process LogoutResponse objects.
> The user lands on the login page with no error.

---

## 4. SAML Mappers (Dedicated Scope)

Keycloak → morpheus-lab → Clients → Morpheus Enterprise → Client Scopes → dedicated → Mappers

### 4.1 `groups` — Group List Mapper

| Field | Value |
|-------|-------|
| Mapper Type | `Group list` |
| Name | `groups` |
| SAML Attribute Name | `groups` |
| SAML Attribute NameFormat | `Basic` |
| Single Group Attribute | **OFF** |
| Full Group Path | **OFF** |

### 4.2 `firstName` — User Attribute Mapper

| Field | Value |
|-------|-------|
| Mapper Type | `User Attribute` |
| Name | `firstName` |
| User Attribute | `firstName` |
| SAML Attribute Name | `firstName` |
| SAML Attribute NameFormat | `Basic` |

### 4.3 `lastName` — User Attribute Mapper

| Field | Value |
|-------|-------|
| Mapper Type | `User Attribute` |
| Name | `lastName` |
| User Attribute | `lastName` |
| SAML Attribute Name | `lastName` |
| SAML Attribute NameFormat | `Basic` |

---

## 5. Morpheus Identity Source Settings

Morpheus → Administration → Identity Sources → Keycloak-SSO

### 5.1 General

| Field | Value |
|-------|-------|
| Type | `SAML SSO` |
| Name | `Keycloak-SSO` |
| Active | Yes |

### 5.2 SAML SSO Configuration

| Field | Value |
|-------|-------|
| **Login Redirect URL** | `https://192.168.41.101:30443/realms/morpheus-lab/protocol/saml` |
| **SAML Logout Redirect URL** | `https://192.168.41.101:30443/realms/morpheus-lab/protocol/saml` |
| Includes SAML Request Parameter | `Yes` |
| POST Binding Mode | ON |
| SAML Request | `Self Signed` |
| SAML Response | `Validate Assertion Signature` |

> ⚠️ **SAML Response Public Key:** After a fresh deployment, copy the updated realm
> certificate from Keycloak and paste it here:
> `Keycloak Admin → morpheus-lab → Realm Settings → Keys → RS256 → Certificate (eye icon)`

### 5.3 Assertion Attribute Mappings

| Field | Value |
|-------|-------|
| Given Name Attribute Name | `firstName` |
| Surname Attribute Name | `lastname` |

### 5.4 Role Mappings

| Field | Value |
|-------|-------|
| Default Role | `Standard User` |
| Role Attribute Name | `groups` |
| Required Role Attribute Value | `mspusers` |
| Admin User Role | *(Assertion Attribute Mapping)* |
| Application Architect Role | `apparchitech` |
| Self Service User Role | `selfservice` |

---

## 6. Redeployment Procedure

```
1. kubectl apply -f keycloak-stack.yaml
2. kubectl rollout status statefulset/keycloak -n keycloak --timeout=10m
3. Keycloak Admin → Create realm: morpheus-lab
4. User Federation → Add LDAP provider (Section 2 settings)
   → Sync LDAP Groups to Keycloak
5. Clients → Create SAML client (Section 3 settings)
   → Advanced tab → Fine Grain SAML Endpoint Configuration (Section 3.4)
   → Client Scopes → dedicated → Add mappers (Section 4)
6. Realm Settings → Keys → Copy RS256 certificate
7. Morpheus → Identity Sources → Keycloak-SSO → Edit (Section 5)
   → Set Login/Logout URLs to HTTPS (Section 5.2)
   → Paste SAML Response Public Key
   → Save Changes
8. Test: morphsrv01.hpetrlab.local → SSO login → emre.baykal
```

---

## 7. Logout Flow

1. User clicks logout in Morpheus
2. Morpheus sends a SAML LogoutRequest to Keycloak (`https://192.168.41.101:30443/realms/morpheus-lab/protocol/saml`)
3. Keycloak terminates the session
4. Keycloak POSTs the LogoutResponse to `https://morphsrv01.hpetrlab.local/login/auth`
5. User lands on the Morpheus login page — no errors

---

## 8. Critical Notes

- **Client Signature Required** must be **OFF** in Keycloak — Morpheus signs requests with a self-signed cert that does not match the cert registered in Keycloak.
- **Group List Mapper** is required — without it, Morpheus cannot see the `mspusers` group and login fails.
- **Valid Redirect URI** must be exactly `https://morphsrv01.hpetrlab.local/externalLogin/callback/N3hbmK5BO` — not `/saml/.../acs`.
- **SP Entity ID** is auto-generated from the Morpheus hostname: `morphsrv01.hpetrlab.local`.
- After redeployment, the realm certificate changes — update the Public Key in the Morpheus Identity Source.
- **Logout Service POST Binding URL** must point to `https://morphsrv01.hpetrlab.local/login/auth` to avoid the Morpheus `GroovyCastException` bug on logout.
