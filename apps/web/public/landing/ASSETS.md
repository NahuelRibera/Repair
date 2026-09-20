Optional image slots for the landing page's editorial sections. Every one
of these is applied as a CSS `background-image` with an always-present
solid/gradient `background-color` fallback underneath (see
`apps/web/src/app/page.tsx`) — so a missing file never shows a broken-image
icon or breaks the layout, it just shows the plain fallback color. None of
this is required for the app to work; it's purely a look-and-feel upgrade
if/when real photography is supplied.

Drop any of these in (any you skip just keeps the fallback color):

```
story-fitment.jpg      — wide, ~1600x1000, "the exact bike you have" section
story-history.jpg      — wide, ~1600x1000, "maintenance that remembers" section
story-every-rider.jpg  — wide, ~1600x1000, "for every kind of rider" section
garage-1.jpg           — ~800x600, "From the garage" card 1
garage-2.jpg           — ~800x600, "From the garage" card 2
garage-3.jpg           — ~800x600, "From the garage" card 3
```

"From the garage" is a small editorial card grid for visual texture only —
it is explicitly not a real blog/CMS, has no links, and nothing here reads
from a database.
