Gerrit multi-primary utilities
==============================

Overview
--------
Set of utilities to manage the setup and administration of Gerrit multi-primary
installation and BAU operations.

Index
-----
* [globalrefdb](globalrefdb.md) - Provides utility to check and update local
refs against the globalrefdb:
 - Checks the repository local-refdb vs. global-refdb
 - Allows updating globalrefdb to a specific sha1 value
* [localalrefdb](localrefdb.md) - Provides ssh commands that create metrics for:
 - Number of refs in project
 - Combined SHA1 of all refs in project
