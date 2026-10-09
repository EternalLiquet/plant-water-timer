# Private ChatGPT MCP adapter

`POST /mcp` is a deliberately narrow JSON-RPC MCP endpoint for one private garden. It is disabled
by default: leaving `MCP_BEARER_TOKEN` empty returns `404` and does not expose tools.

## Deployment precondition

Before adding this endpoint to ChatGPT, an operator must provide a private HTTPS route to this
application and configure a **new, plant-only** high-entropy `MCP_BEARER_TOKEN` in the plant app's
secret store. Do not reuse a SparkyFitness credential, connection, OAuth client, tunnel token, or
any other service secret. ChatGPT must use the dedicated value in the HTTPS authorization bearer header.

`MCP_OWNER_USERNAME` selects the existing garden owner whose data the adapter can access (default
`gardener`). It must match the signed-in garden account that owns the intended data. The server
uses the same deterministic owner ID as the garden application; every query and mutation is
scoped to it. The legacy `X-Owner-Id` routes remain denied and are not an authentication boundary.

This draft does not create a public route, OAuth client, tunnel, ChatGPT connection, or credentials.
It does not make the endpoint suitable for a multi-user deployment: replace the single bearer
configuration with a real per-user authentication/authorization boundary first.

## Supported tools

- `list_plants`, `get_plant`
- `get_plant_photo` — returns an MCP `image` content item with base64 JPEG bytes, never a URL
- `confirm_plant_name`, `create_plant`, `log_watering`

The three mutation tools require UUID `requestId` values. Repeating an identical create or watering
request returns the prior saved state. Repeating a name confirmation is recorded in a small
owner-scoped receipt table; reusing its request ID with different values returns a conflict.
Inputs are bounded and validated before writes.

No arbitrary database queries, filesystem access, photo uploads, deletion, plant editing beyond a
confirmed display name, observations, or OpenAI APIs are exposed.

## Remaining limitations

This is a minimal server-side MCP adapter, not ChatGPT provisioning. The operator must arrange
private HTTPS and store/configure its dedicated bearer secret before connecting it. The current
single-owner mapping intentionally keeps the scope small; a production multi-user MCP deployment
needs identity-bound tokens, token rotation/revocation, audit controls, and an externally reviewed
network boundary.
