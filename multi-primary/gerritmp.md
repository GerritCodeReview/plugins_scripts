Gerrit multi-primary utilities
==============================

Set of utilities to manage the setup and administration of Gerrit multi-primary
installation and BAU operations.

NAME
----
gerritmp check-globalrefdb check the repository refs status against the global-refdb

SYNOPSIS
--------
>     ssh -p <port> <host> gerritmp check-globalrefdb PROJECT [--ref <ref name>] [--verbose]

DESCRIPTION
-----------
Verify if the local with the global refs for a project, reporting all differences
between the two SHA1s. The operation can operate on the individual ref passed as
a parameter.

ACCESS
------
Any user who has been granted the 'Administrate Server' capability.

SCRIPTING
---------
This command is intended to be used in scripts.

EXAMPLES
--------
Check if all refs of the All-Users project are up-to-date with the global-refdb:

>     $ ssh -p 29418 review.example.com gerritmp check-globalrefdb All-Users
