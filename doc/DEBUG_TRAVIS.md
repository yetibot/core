# Debug Travis

Sometimes the build fails. Here are the steps to debug.

[Travis docs](https://docs.travis-ci.com/user/running-build-in-debug-mode/)

```bash
read -s token

id=244405005

curl -s -X POST \
       -H "Content-Type: application/json" \
       -H "Accept: application/json" \
       -H "Travis-API-Version: 3" \
       -H "Authorization: token $token" \
       -d "{\"quiet\": true}" \
       https://api.travis-ci.com/job/${id}/debug

```
