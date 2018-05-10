# GraphQL API

## Examples

Hit the GraphQL with curl:

```bash
curl 'http://localhost:3003/graphql' \
  -H 'Accept: application/json' \
  --data 'query=%7Beval(expr%3A%20%22uptime%22)%7D' \
  --compressed
```
