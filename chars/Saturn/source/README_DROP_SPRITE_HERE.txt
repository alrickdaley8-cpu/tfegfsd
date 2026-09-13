DROP YOUR SPRITE SHEET HERE
===========================

Save your Saturn sprite sheet image into THIS folder (chars/Saturn/source/),
then tell the agent "it's in chars/Saturn/source/".

Any filename works, e.g.:
    chars/Saturn/source/sprite_sheet.png

The agent will then run:
    python3 tools/ingest_sprite.py --input chars/Saturn/source/sprite_sheet.png --preview

...which splits the sheet, removes the background, normalizes frames, builds
palettes, and repacks Saturn.sff automatically.
