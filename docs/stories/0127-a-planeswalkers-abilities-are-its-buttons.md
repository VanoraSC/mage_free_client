# 0127 — A planeswalker's abilities are its buttons

- **Story:** #229
- **Epic:** EPIC-19 — Game Board Rebuild
- **Specified by:** Pete — *"On the details page for the planeswalker, I want to see the buttons for the
  activated abilities in a vertical column above the flavor text instead of the 'play' button."* And, after
  Liliana, Death's Majesty showed only her +1: *"grey them out so they're always present, even if you can't
  use them."*

## 1. Objective

A planeswalker of yours shows one button per loyalty ability on its detail, in a column, always. The ones
the server is offering right now can be pressed, in place of the single Play button, and activate that
ability; the rest are drawn greyed.

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

**The playable list leaves out what cannot be activated now.** Liliana, Death's Majesty's −3 carries a
`TargetCardInYourGraveyard` for a creature card; with none there it has nothing to target and is not
listed, and her −7 is not listed below seven loyalty. Which abilities the planeswalker *has* is its rules
text instead.

**A loyalty ability's rules line has a fixed shape.** `AbilityImpl.getRule` writes the costs, `": "`, then
the effect. A `LoyaltyAbility`'s one cost is a `PayLoyaltyCost` — text `Integer.toString(amount)` with `+`
in front of a positive amount: `+1`, `0`, `-3`, with an ASCII hyphen — or a `PayVariableLoyaltyCost`,
text `-X`.

## 3. Design

- **Buttons.** For a planeswalker of the viewer's, one button per rules line that starts with a loyalty
  cost and a colon, in the card's order — always. One the server is offering (an `abilityIds` entry whose
  clipped name starts that line) can be pressed; the rest are greyed and a press does nothing. An offered
  ability that is no loyalty line of the card's is still a button, after them. A line that became a
  button is not also listed as text.
- **Play is replaced only when a button can be pressed.** A planeswalker none of whose abilities can be
  pressed keeps whatever action it has, so one that is the target of a spell can still be picked.
- **Where.** In a column in the detail panel, after the permanent's abilities, stats and attachments and
  before the printed text, replacing the single action button.
- **What a press does.** `BoardAction.ActivateAbility(objectId, abilityId)`: the ViewModel sends Play for
  the planeswalker and remembers which ability was pressed. When the server's ability question arrives
  and offers that id, the ViewModel answers it with that id. Any other new question drops the memory, so
  a question the server did not expect to be answered for the player is left to the player.
- **Only the viewer's planeswalkers.** Other permanents keep the Play button, as Pete asked; an opponent's
  planeswalker is read as text, because none of its buttons could ever be pressed.

## 4. Acceptance criteria

- [ ] Every loyalty ability of your planeswalker is a button on its detail, whether or not it can be used.
- [ ] The abilities the server is offering can be pressed, and there is no Play button beside them.
- [ ] The others are greyed, and pressing one does nothing.
- [ ] Each button reads as the ability's rules text.
- [ ] Pressing one activates that ability without a second question.
- [ ] If the server's question does not offer that ability, it is left on screen for the player.
- [ ] A planeswalker of yours that is the target of your spell can still be picked from its detail.
