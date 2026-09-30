# Scout Mesh \(non\-Tailscale\) in\-APK VPN
Replace Tailscale coupling with a productized **Scout Mesh**: WireGuard hub\-and\-spoke, one\-time mesh entry entitlement, subscription\-gated stack APIs, and an Android `VpnService` shipped inside the Scout APK \(no separate VPN client\)\.
## Problem
Today phones prefer Tailscale `100.x` endpoints \(`AppPrefs.TAILSCALE_BASE_URL`\) and assume the Tailscale app is installed\. That blocks a paid mesh product where the only download is the Scout navigation APK\.
## Approach
* **Transport:** WireGuard \(UDP\) hub\-and\-spoke\. Simple, auditable, no third\-party control plane\.
* **Addressing:** Private mesh `10.66.0.0/16` \(hub `10.66.0.1`\)\. Stack services advertise mesh IPs, not Tailscale CGNAT\.
* **Client:** Android `VpnService` \+ WireGuard tunnel library **embedded in the Scout APK**\. OS VPN consent dialog only — no Play Store VPN app\.
* **Entry fee:** Enrollment mints a peer \(keys \+ IP \+ allowed IPs\) after a one\-time license/token check\.
* **Subscription:** Mesh membership ≠ full stack access\. API calls carry a subscription token; unpaid peers can only hit enrollment/health/bootstrap\.
## Deliverables \(this pass\)
1. Design \+ ops docs under `docs/guides/SCOUT_MESH.md` and hub scripts in `stack/mesh/`\.
2. Backend: mesh config in bootstrap \+ `/api/mesh/enroll` \+ `/api/mesh/profile` \(dev\-token mode first\)\.
3. Android: `ScoutMeshVpnService`, prefs/UI rename from Tailscale → Scout Mesh, default base URL `http://10.66.0.1:18080`\.
4. Env knobs in `vehicle_stack.env` for mesh advertise host / CIDR allowlist\.
## Non\-goals \(this pass\)
* Real payment processor wiring \(Stripe etc\.\) — pluggable license verifier stub only\.
* Full peer\-to\-peer mesh \(phones talk through hub\)\.
* Removing Tailscale from internal lab docs until cutover is verified\.
## Cutover notes
* Keep Tailscale as optional lab fallback via prefs\.
* Hub must expose public UDP \(WireGuard\) \+ HTTPS enrollment endpoint reachable without mesh\.
* Android cleartext still allowed for mesh RFC1918 via existing network security config\.
