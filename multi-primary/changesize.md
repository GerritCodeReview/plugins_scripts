Changesize utility
==============================

COMMANDS
----
`changesize size` utility to calculate the size of a specific change


SYNOPSIS
--------
>     ssh -p <port> <host> changesize size PROJECT REF-NAME [--verbose]

DESCRIPTION
-----------

## Size
Calculate the cumulative size of all blobs in a specific ref.

ACCESS
------
Any user who has been granted the 'Administrate Server' capability.

SCRIPTING
---------
This command is intended to be used in scripts.

EXAMPLES
--------
Check if all refs of the All-Users project are up-to-date with the global-refdb:

>     $ ssh -p 29418 review.example.com changesize size proj-name refs/changes/01/01/1
