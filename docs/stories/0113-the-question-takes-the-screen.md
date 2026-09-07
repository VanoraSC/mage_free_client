# 0113 — The question takes the screen

- **Story:** #199
- **Epic:** EPIC-19 — Game Board Rebuild
- **Depends on:** 0112 (the board this redesigns the prompts on).
- **Specified by:** [`docs/ui-modernization-plan.md`](../ui-modernization-plan.md) §7.1 (gestures), §7.6
  (the cast flow), §7.4 (the board's floating layers).

## 1. Objective

Redesign the prompt surfaces for the landscape board. A question answered from its own content takes
the screen; a question answered by touching the board keeps out of the way; and a card is chosen by
pressing the card.

## 2. Context & background

**0112 borrowed the portrait board's panel, and said so.** It was the right call to get a real game
on the rebuilt board without redesigning two things at once, and it left a panel that is wrong here in
three specific ways — all of them found by playing.

**A card is chosen by pressing a button labelled "Choice 4".** When the server asks the viewer to pick
a card that is not on the battlefield — a library search, a graveyard pick — the prompt carries the
cards with it. The panel draws them, and *also* draws a numbered button per card, because
`offBoardCandidateButtons` counts anything the board does not draw as unnameable. The cards are right
there, in the same panel, being drawn. Offering the same choice twice — once as a picture and once as
"Choice 4" — is what makes the picture look decorative.

**The panel is small when it is the whole game.** It is capped at 200dp and sits in a corner, which is
right for a prompt the player answers by tapping the battlefield and wrong for one where the panel *is*
the interaction. A row of seven cards at 72dp each, in a panel taking a third of the width, is the
board apologising for the question it is asking.

**Leaving the game and conceding are inside the answer panel.** Both are reachable only through a panel
that exists to answer the server's question, and both are a different kind of act — you can want to
leave a game at a moment when there is nothing to answer at all. The exit is a floating X in the same
corner, so the corner now holds two unrelated controls and a panel.

## 3. Scope

**In scope**
- A prompt answered **from its own content** takes the screen: full width, and as much height as it
  needs for its cards to be read.
- A prompt answered **by touching the board** stays out of the board's way, as now.
- Choosing a card is pressing the card: it opens the same detail view every other card on the board
  opens, and the detail asks to confirm.
- The numbered buttons for candidates the prompt itself carries, retired.
- The panel's own heading and its inline game menu, retired.
- A menu in the top-right corner holding the game menu and the exit.
- The collapse control says what it does: *Show battlefield* when the panel is up, *Show controls*
  when it is down.

**Out of scope**
- **Where a cast in flight is drawn**, and how targeting reads on the battlefield itself. Both are
  worth doing and neither is what this story is about.
- **The amount and multi-amount controls.** They are answered from their own content already and are
  the right size for what they ask.

## 4. Prerequisites & toolchain

Project baseline; `:feature:game`.

## 5. Design & approach

**The rule is one the model already carries.** `PromptControlsUi` distinguishes a prompt answered from
its own content from one answered by touching the board, and says so in its own KDoc — the difference
"survives as `pickableObjectIds` being empty or not, and nothing else". So the panel does not need a
new flag: it takes the screen exactly when the prompt carries its own cards, and keeps its corner
otherwise.

That is what makes the two cases safe to treat differently. A priority prompt is answered by pressing
a card **in the hand**, so a panel over the hand would cover the answer to its own question — the
defect 0112 found. A library search is answered by pressing a card **in the panel**, so the panel
covering the board costs nothing, and the collapse control is there for the moment the player wants to
check the board before deciding.

**A card is pressed, and the detail confirms it.** The same gesture as everywhere else on this board:
a press raises the card, and the raised card is where the act is committed. It matters more here than
anywhere, because these are the cards a player has *not* seen — a search is a choice between cards
being read for the first time, at a size a row of thumbnails cannot give them.

**The corner holds one control.** A menu, with the game menu's own items and the exit under it.
Concede and quit already confirm; leaving does not need to, because it leaves the game running and the
room is still under it.

## 6. Implementation steps

1. Candidates the prompt carries stop producing numbered buttons.
2. The candidate row draws pressable cards and nothing else.
3. The detail view resolves a card the prompt carried, and confirms it.
4. The panel sizes itself from the kind of question it is asking.
5. The corner menu, and the panel's heading and game-menu button retired.

## 7. Testing & verification

- **Hermetic Compose:** a prompt carrying cards offers no numbered button for them; pressing a
  candidate raises it rather than answering; confirming from the detail sends the action the controls
  named; a prompt answered from its own content is wider than one answered on the board; the corner
  menu reaches concede, quit and the exit.
- **Eyes-on:** a real game with a library search in it.

## 8. Acceptance criteria

- [ ] Choosing a card is pressing the card, and the choice is confirmed on the card's own detail.
- [ ] No numbered "Choice N" button is offered for a card the prompt drew.
- [ ] A prompt that carries cards uses the width of the screen.
- [ ] A prompt answered on the board does not cover the hand.
- [ ] The collapse control says *Show battlefield*, and *Show controls* once collapsed.
- [ ] The game menu and the exit are in one corner menu, and reachable with no prompt outstanding.
