Global-refdb Check utility
==============================

NAME
----
`globalrefdb check` utility compare the repository refs status against the global-refdb.

SYNOPSIS
--------
>     ssh -p <port> <host> globalrefdb check PROJECT [--ref <ref name>] [--verbose]

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

>     $ ssh -p 29418 review.example.com globalrefdb check All-Users
