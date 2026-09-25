# Release 2: the kind filter

A plan for the second of the five releases agreed after the kinds shipped (v0.6.40). Written before the
work, kept while it was in progress.

**Shipped in v0.6.44**, and the one thing it left behind - per-kind drawing and markers on the map - went
in after it instead of waiting its turn, as release 3 (v0.6.45, with how a marker is drawn settled over
v0.6.46 to v0.6.49; see `map-by-kind.md`). The decisions are in the README under *The kinds of asset*;
the evidence is in `build/verify/kind-filter.txt`.

## Why

Eight kinds of asset are worth having only if the work can be read one kind at a time. Today:

- the phone's Assets list has no filter at all: blocks, then everything;
- the desk's box marked *Find an asset or block* matches names only;
- the map has the four layer switches (tracks, roads, fencelines, places), which are coarser than a
  kind: five kinds share *Places*.

## What ships

**The phone's list.** A one-line, sideways-scrolling row of chips between the *Draw*/*Import* buttons
and the list:

    All | Track | Road | Fenceline | Building | Sign | Bench seat | Picnic table | Other place

- **All** is first and is what an untouched install shows, so nothing changes for anyone who never
  taps one.
- Tapping a kind shows only that kind: the rows *and* the block tiles' counts, so a block says what it
  has of that kind rather than what it has of everything. A block with none of that kind goes.
- Tapping the chosen kind again goes back to All - the chips toggle, so there is no trap.
- A kind with nothing in it says so in one line rather than showing an empty list: "Nothing of that
  kind on the phone yet."
- **Not stored.** The map's layer switches are a way of working and are remembered; this is a question
  asked now ("where are my buildings?"), so it lives in the view model and a restart opens on the
  whole work. No preference, no migration, no change to an install that never touches it.

**The desk.** A *Type* select beside the search box: *Anything* (the default) plus the eight kinds, in
the phone's words, out of the state document the page already fetches. The list shows what matches both
the typed words and the type.

## The one rule, in one place

`AssetKindFilter` (`domain/asset`) answers *is this row shown*, and it answers it with
`AssetKind.fromStorage`, so a track drawn before the kinds existed - stored as `INFRASTRUCTURE` - is
found under **Fenceline** when it is a line and under **Other place** when it is a spot. A filter that
compared the stored word instead would hide every old track from both.

For the desk, the same problem is solved at the source: **the phone sends the resolved kind** in the
editor's document (`WebEditorJson`), so the page never has to hold a second copy of that rule - which
is exactly the mistake v0.6.43 fixed in the page's wording.

## Not in this release

- **Per-kind drawing on the map, and per-kind markers.** The map's four layers are groups of kinds;
  filtering the map by kind means one layer per kind, which is release 5. Until then the map keeps its
  four switches, unchanged.
- **Remembering the filter.** See above: not stored.
- A *show only this* jump from an asset's page to the filtered list. Tempting, and it can follow once
  the filter itself is in use.

## What to prove, and how

- The phones's list, by screenshot: unfiltered, filtered to a kind that exists, filtered to a kind
  that does not (the one-liner), and back to All - with the block counts changing with it.
- The rule, by test: a legacy `INFRASTRUCTURE` row is found under Fenceline as a line and under Other
  place as a spot.
- The desk, by a browser dump: the Type select set to a kind, the list showing only that kind.
