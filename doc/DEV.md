# Yetibot development guide

## Writing commands

## Working on the dashboard

The dashboard is built with React and Redux. Because these tools are native to
the NodeJS ecosystem, I didn't attempt to wire it into `lein`. Instead I use the
canonical tools - in this case `yarn` - to run a webpack dev server with hot
reloading.

In production these assets are compiled down to plain JS and served by `ring`.

To run the dev server:

```bash
yarn install
yarn dev
```
