# Ogiri Domain Language

## Subject

A stable identity that may own sessions. A Subject is identified by Realm, optional Tenant, and opaque Subject ID. Mutable login identifiers such as email addresses are not Subject IDs.

## Realm

A named authentication population with its own authority and identity rules. Equal Subject IDs in different Realms identify different Subjects.

## Tenant

An optional namespace within a Realm. Equal Subject IDs in different Tenants identify different Subjects.

## Session

A revocable relationship between one Subject and one Client. A Session has a stable Session ID, one credential family, lifecycle timestamps, and a monotonically increasing Version.

## Client

A caller installation or device label that owns one Session independently of a Subject's other Clients. A Client label is metadata, not proof of possession.

## Session ID

A stable, non-secret identity used to list, revoke, and audit a Session. It does not authenticate a caller.

## Selector

A random, non-secret credential prefix used to locate one Session without first identifying the Subject.

## Verifier

The high-entropy secret portion of a session credential. Possession proves authority to use the Session. Only a digest crosses the storage boundary.

## Credential version

The current or immediately previous Verifier state for a Session. A previous version always has one fixed validity deadline.

## Credential family

The lineage of credential versions belonging to one Session. Reuse detection revokes the family rather than accepting an older lineage member.

## Revocation

An authoritative state transition that makes a Session unusable. Revocation may target one Session or every Session owned by a Subject.

## Issued session

The one-time result that pairs committed Session metadata with a plaintext credential for transport. It is distinct from a stored Session.
