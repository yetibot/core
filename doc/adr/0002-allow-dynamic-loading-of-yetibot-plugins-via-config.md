# 2. Allow dynamic loading of Yetibot plugins via config

Date: 2020-10-21

## Status

Accepted

## Context

Yetibot currently resides across two primary repos:

- github.com/yetibot/yetibot
- github.com/yetibot/yetibot-core

These code bases continue to grow in size, and consist of a diverse range of
features, many of which many users won't care to use.

## Decision

Switching to a plugin system allows us to split up the code base into much more
fine grained, logical units. For example, we may split the `github` command into
its own plugin.

The first plugin is [yetibot-kroki](https://github.com/yetibot/yetibot-kroki).

We will continue to extract plugins from both of the above code bases.

## Consequences

The primary `yetibot` jar and docker image artifacts will decrease in size as
features are extracted into separate plugins.

Any configured dynamic plugins will be resolved upon startup. This means
potentially longer startup times, especially if the user has configured many
plugins.
