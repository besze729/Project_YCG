Collect all boss and mini-boss encounters from the specified game.

Game:
<?GAME_TITLE>

Search only:
<?SOURCE_SITE>

Scope rules:

- Include content only from the specified game.
- Do not include bosses from sequels, prequels, remakes, spin-offs, expansions belonging to another standalone game, or other games in the same franchise.
- DLC and expansions belonging to the specified game are allowed.
- Before adding an encounter, verify that the source clearly associates it with the specified game.
- If the source does not clearly establish that the encounter belongs to the specified game, do not include it.

Include:

- main bosses
- mini-bosses
- optional bosses
- DLC bosses

Return every boss name you can verify from the allowed source.

Do not omit entries because classification is uncertain.
If classification cannot be verified, set it to null.

Use the exact boss name shown by the source.
Do not invent, normalize, rename, or merge boss names.

Return JSON only:

{
  "bosses": [
    {
      "displayName": "<BOSS_NAME>",
      "classification": "<MAIN_BOSS | MINI_BOSS | null>"
    }
  ],
  "sources": [
    "<SOURCE_URL>"
  ]
}