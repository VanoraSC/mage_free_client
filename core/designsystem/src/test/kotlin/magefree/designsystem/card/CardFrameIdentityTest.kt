package magefree.designsystem.card

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which frame a card is drawn in.
 *
 * The assertions worth having are the ones a plain "read the first colour symbol" would get wrong: a
 * hybrid card, a Phyrexian one, a land that taps for two colours, and an artifact whose cost is all
 * generic. Reading a mono-coloured cost is a lookup and a test of it is a test of nothing.
 */
class CardFrameIdentityTest {
    @Test
    fun `a mono-coloured cost takes that colour`() {
        assertEquals(CardFrameIdentity.Green, cardFrameIdentity("{1}{G}", "Creature — Bear"))
        assertEquals(CardFrameIdentity.White, cardFrameIdentity("{W}", "Creature — Bird"))
    }

    @Test
    fun `two colours in a cost is gold, however they are spelled`() {
        assertEquals(CardFrameIdentity.Gold, cardFrameIdentity("{1}{W}{U}", "Creature — Human"))
        // Hybrid: one symbol, two colours. A frame that read only the first would call this white.
        assertEquals(CardFrameIdentity.Gold, cardFrameIdentity("{W/U}", "Instant"))
    }

    @Test
    fun `a hybrid of one colour and generic is still that one colour`() {
        // `{2/W}` is payable with two of anything *or* one white. It is a white card.
        assertEquals(CardFrameIdentity.White, cardFrameIdentity("{2/W}{2/W}", "Creature — Spirit"))
    }

    @Test
    fun `a Phyrexian symbol carries its colour`() {
        // `{G/P}` is green, and the frame of every Phyrexian card says so.
        assertEquals(CardFrameIdentity.Green, cardFrameIdentity("{1}{G/P}", "Instant"))
    }

    @Test
    fun `a cost of nothing but generic is the artifact grey`() {
        assertEquals(CardFrameIdentity.Colorless, cardFrameIdentity("{4}", "Artifact"))
        assertEquals(CardFrameIdentity.Colorless, cardFrameIdentity("{X}{C}", "Sorcery"))
    }

    @Test
    fun `a card with no cost at all is colourless rather than nothing`() {
        assertEquals(CardFrameIdentity.Colorless, cardFrameIdentity(null, "Creature — Elemental"))
        assertEquals(CardFrameIdentity.Colorless, cardFrameIdentity("", null))
    }

    @Test
    fun `every land is the land frame, whatever it taps for`() {
        // A basic is not the colour it makes and a dual is not gold: a land has its own frame, in
        // every set that prints one.
        assertEquals(CardFrameIdentity.Land, cardFrameIdentity(null, "Basic Land — Forest"))
        assertEquals(CardFrameIdentity.Land, cardFrameIdentity(null, "Land"))
        assertEquals(CardFrameIdentity.Land, cardFrameIdentity(null, "Land — Forest Island"))
    }

    @Test
    fun `a land that is currently a creature is still a land`() {
        // The type line is the server's, after continuous effects. An animated Mutavault attacks, and
        // it is still the card in the land frame.
        assertEquals(CardFrameIdentity.Land, cardFrameIdentity(null, "Land Creature — Elemental"))
    }

    @Test
    fun `an artifact land is a land, not an artifact`() {
        assertEquals(CardFrameIdentity.Land, cardFrameIdentity(null, "Artifact Land"))
    }
}
