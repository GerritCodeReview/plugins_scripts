Gerrit administration scripts
=============================

Overview
--------
Scripts for the daily administration of Gerrit.

Index
-----
* [reindexer](cmd-reindexer.md) - Allows to recreate the Lucene index using the on-line reindex
* [warm-cache-1.0.groovy](/admin/warm-cache-1.0.groovy) - Controls the Gerrit cache warm-up via command-line
* [readonly-1.0.groovy](/admin/readonly-1.0.groovy) - Set all Gerrit projects in read-only mode during maintenance
* [stale-packed-refs-1.0.groovy](/admin/stale-packed-refs-1.0.groovy) - Check all projects and remove stale `packed-refs.lock` files
