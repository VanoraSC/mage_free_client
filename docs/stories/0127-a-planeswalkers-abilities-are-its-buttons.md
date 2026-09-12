# 0127 — A planeswalker's abilities are its buttons

- **Story:** #229
- **Epic:** EPIC-19 — Game Board Rebuild
- **Specified by:** Pete — *"On the details page for the planeswalker, I want to see the buttons for the
  activated abilities in a vertical column above the flavor text instead of the 'play' button."*

## 1. Objective

When the server is offering a planeswalker's abilities, its detail shows one button per ability, in a
column, in place of the single Play button. Pressing one activates that ability.

## 2. What upstream knows

**Which abilities may be activated is already on the wire.** `GameView.canPlayObjects` is a
`PlayableObjectsList`: per object, a `PlayableObjectStats` holding a record per playable ability — its
`ability.getId()` and `ability.toString()` clipped to fifty characters. The bridge carries both as
`PlayableObject.abilityIds` and `abilityNames`.

**Activating one is a two-step exchange upstream, and it cannot be one.** A press sends the object's id
(`sendPlayerUUID`). `HumanPlayer.priority()` collects the object's usable abilities and calls
`activateAbility(abilities, object, game)`, which asks *"Choose spell or ability to play"* with a
`GAME_CHOOSE_ABILITY` prompt and activates `abilities.get(responseId)`. The picker is suppressed only for
a land play, an alternative cost, or a mana ability (`suppressAbilityPicker`) — never for a loyalty
ability — so the question is always asked, even with a single ability offered. The ids it offers are the
same `ability.getId()`s the playable list carries.

## 3. Design

- **Buttons.** For a planeswalker the server is offering, one button per `abilityIds` entry. The label is
  the permanent's own rules line that upstream's clipped name is the start of, and the clipped name
  itself when no line matches. A line that became a button is not also listed as text.
- **Where.** In a column in the detail panel, after the permanent's abilities, stats and attachments and
  before the printed text, replacing the single action button.
- **What a press does.** `BoardAction.ActivateAbility(objectId, abilityId)`: the ViewModel sends Play for
  the planeswalker and remembers which ability was pressed. When the server's ability question arrives
  and offers that id, the ViewModel answers it with that id. Any other new question drops the memory, so
  a question the server did not expect to be answered for the player is left to the player.
- **Only planeswalkers.** Other permanents keep the Play button; Pete asked for planeswalkers.

## 4. Acceptance criteria

- [ ] A planeswalker whose abilities are offered shows a button per ability, and no Play button.
- [ ] Each button reads as the ability's rules text.
- [ ] Pressing one activates that ability without a second question.
- [ ] If the server's question does not offer that ability, it is left on screen for the player.
- [ ] A planeswalker with nothing offered shows no buttons.
