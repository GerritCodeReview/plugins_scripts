Global-refdb utility
==============================

COMMANDS
----
`globalrefdb check` utility compare the repository refs status against
the global-refdb.
`globalrefdb update-ref` utility to manually update global-refdb entry
in the eventuality that all nodes and global-refdb are out of sync.


SYNOPSIS
--------
>     ssh -p <port> <host> globalrefdb check PROJECT [--ref <ref name>] [--verbose]
>     ssh -p <port> <host> globalrefdb update-ref PROJECT REF-NAME NEW-VALUE

DESCRIPTION
-----------

## Check
Verify if the local with the global refs for a project, reporting all differences
between the two SHA1s. The operation can operate on the individual ref passed as
a parameter.

## Update-ref
Update global-refdb in case we cannot automatically deduce which ref is
the latest.
This can happen if the global-refdb finds itself behind other nodes.

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

Update global-refdb's sha1

>     $ ssh -p 29418 review.example.com globalrefdb update-ref All-Users refs/draft-comments/nn/nnnn/nn <sha1>
