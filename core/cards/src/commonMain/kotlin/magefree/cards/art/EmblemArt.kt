package magefree.cards.art

/*
 * Emblem art.
 *
 * **An emblem names no printing.** `Emblem.setSourceObjectAndInitImage` gives it the set code upstream
 * chose for its *image* (from `tokens-database.txt`), an empty card number and an image number, and
 * `EmblemView` carries exactly that. Nothing on it — no id, set or number — names the card that
 * created it, so that printing is not on the wire and cannot be put there: the bridge reads views, and
 * no view holds it.
 *
 * **Upstream's own client answers this with a table, and this is that table.** Its `ScryfallImageSource`
 * resolves every token and emblem through `ScryfallImageSupportTokens.findTokenLink(set, name,
 * imageNumber)` — a hand-maintained map from `SET/Name`, or `SET/Name/N` where one set has two images of
 * one name, to a Scryfall link. Every emblem entry is a plain `cards/{set}/{number}` link, so each is
 * carried here as the printing it names, and resolves through the same path as any other card rather
 * than through a second URL builder.
 *
 * **Ported, not derived.** Scryfall does not call these what upstream does (`Emblem Nixilis` is
 * `Ob Nixilis Reignited Emblem` there), which is why the by-name lookup ordinary tokens use cannot find
 * them. Where upstream has no entry — eight of the emblems in its token database today — it has no image
 * either, and the placeholder is the honest answer.
 *
 * Source: `Mage.Client/src/main/java/org/mage/plugins/card/dl/sources/ScryfallImageSupportTokens.java`
 * at upstream ref `e0fe4b6f6a`, every `put` whose name begins `Emblem`.
 */

/**
 * The image request for an **emblem**, or `null` when upstream has no image for it either.
 *
 * Keyed exactly as `findTokenLink` keys it, so an emblem this finds is one upstream's client draws, and
 * one it does not find is one upstream's client does not draw.
 *
 * @param setCode the set upstream chose for the emblem's image, as the server sends it — not the set of
 *   the card that made it.
 * @param name the emblem's name as the server sends it, `Emblem Liliana`.
 * @param imageNumber which of two same-named emblems in one set this is, and `0` where the set has one.
 *   Only Commander Masters prints two today: its two `Emblem Chandra`s.
 */
fun emblemArtRequest(
    setCode: String,
    name: String,
    imageNumber: Int = 0,
    size: CardArtSize = CardArtSize.SMALL,
): CardArtRequest? {
    val key = if (imageNumber == 0) "$setCode/$name" else "$setCode/$name/$imageNumber"
    val (set, number) = EMBLEM_PRINTINGS[key] ?: return null
    return CardArtRequest(setCode = set, collectorNumber = number, size = size)
}

/** Upstream's emblem key to the Scryfall set and collector number its link names, in upstream's order. */
internal val EMBLEM_PRINTINGS: Map<String, Pair<String, String>> =
    mapOf(
        "RIX/Emblem Huatli" to ("trix" to "5"),
        "RNA/Emblem Domri" to ("trna" to "13"),
        "GRN/Emblem Ral" to ("tgrn" to "7"),
        "GRN/Emblem Vraska" to ("tgrn" to "8"),
        "DOM/Emblem Jaya Ballard" to ("tdom" to "15"),
        "DOM/Emblem Teferi" to ("tdom" to "16"),
        "AKH/Emblem Gideon" to ("takh" to "25"),
        "AER/Emblem Tezzeret" to ("taer" to "4"),
        "KLD/Emblem Chandra" to ("tkld" to "10"),
        "KLD/Emblem Dovin" to ("tkld" to "12"),
        "KLD/Emblem Nissa" to ("tkld" to "11"),
        "EMN/Emblem Liliana" to ("temn" to "9"),
        "EMN/Emblem Tamiyo" to ("temn" to "10"),
        "SOI/Emblem Arlinn" to ("tsoi" to "18"),
        "SOI/Emblem Jace" to ("tsoi" to "17"),
        "BFZ/Emblem Gideon" to ("tbfz" to "12"),
        "BFZ/Emblem Kiora" to ("tbfz" to "14"),
        "BFZ/Emblem Nixilis" to ("tbfz" to "13"),
        "WAR/Emblem Nissa" to ("twar" to "19"),
        "MH1/Emblem Serra" to ("tmh1" to "20"),
        "MH1/Emblem Wrenn" to ("tmh1" to "21"),
        "M19/Emblem Ajani" to ("tm19" to "15"),
        "M19/Emblem Tezzeret" to ("tm19" to "16"),
        "M19/Emblem Vivien" to ("tm19" to "17"),
        "M20/Emblem Chandra" to ("tm20" to "11"),
        "M20/Emblem Yanling" to ("tm20" to "12"),
        "C19/Emblem Nixilis" to ("tc19" to "29"),
        "ELD/Emblem Garruk" to ("teld" to "19"),
        "IKO/Emblem Narset" to ("tiko" to "12"),
        "M21/Emblem Basri" to ("tm21" to "16"),
        "M21/Emblem Garruk" to ("tm21" to "17"),
        "M21/Emblem Liliana" to ("tm21" to "18"),
        "KHM/Emblem Kaya" to ("tkhm" to "20"),
        "KHM/Emblem Tibalt" to ("tkhm" to "21"),
        "KHM/Emblem Tyvar" to ("tkhm" to "22"),
        "STX/Emblem Lukka" to ("tstx" to "8"),
        "STX/Emblem Rowan" to ("tstx" to "9"),
        "AFR/Emblem Ellywick" to ("tafr" to "16"),
        "AFR/Emblem Lolth" to ("tafr" to "17"),
        "AFR/Emblem Mordenkainen" to ("tafr" to "18"),
        "AFR/Emblem Zariel" to ("tafr" to "19"),
        "MID/Emblem Teferi" to ("tmid" to "17"),
        "MID/Emblem Wrenn" to ("tmid" to "18"),
        "VOW/Emblem Chandra" to ("tvow" to "20"),
        "MMA/Emblem Elspeth" to ("tmma" to "16"),
        "NEO/Emblem Kaito" to ("tneo" to "18"),
        "NEO/Emblem Tezzeret" to ("tneo" to "19"),
        "DTK/Emblem Narset" to ("tdtk" to "8"),
        "C14/Emblem Daretti" to ("tc14" to "36"),
        "C14/Emblem Nixilis" to ("tc14" to "35"),
        "C14/Emblem Teferi" to ("tc14" to "34"),
        "C16/Emblem Daretti" to ("tc16" to "21"),
        "MED/Emblem Dack" to ("tmed" to "R2"),
        "MED/Emblem Domri" to ("tmed" to "R3"),
        "MED/Emblem Elspeth" to ("tmed" to "G4"),
        "MED/Emblem Garruk" to ("tmed" to "W3"),
        "MED/Emblem Jaya Ballard" to ("tmed" to "R4"),
        "MED/Emblem Liliana" to ("tmed" to "G5"),
        "MED/Emblem Ral" to ("tmed" to "G6"),
        "MED/Emblem Tamiyo" to ("tmed" to "R5"),
        "MED/Emblem Teferi" to ("tmed" to "G7"),
        "MED/Emblem Vraska" to ("tmed" to "G8"),
        "BBD/Emblem Rowan Kenrith" to ("tbbd" to "8"),
        "BBD/Emblem Will Kenrith" to ("tbbd" to "7"),
        "CM2/Emblem Daretti" to ("tcm2" to "18"),
        "M15/Emblem Ajani" to ("tm15" to "13"),
        "M15/Emblem Garruk" to ("tm15" to "14"),
        "M14/Emblem Garruk" to ("tm14" to "13"),
        "M14/Emblem Liliana" to ("tm14" to "12"),
        "M13/Emblem Liliana" to ("tm13" to "11"),
        "DKA/Emblem Sorin" to ("tdka" to "3"),
        "DDI/Emblem Koth" to ("tddi" to "2"),
        "DDI/Emblem Venser" to ("tddi" to "1"),
        "AVR/Emblem Tamiyo" to ("tavr" to "8"),
        "GTC/Emblem Domri" to ("tgtc" to "8"),
        "THS/Emblem Elspeth" to ("tths" to "11"),
        "BNG/Emblem Kiora" to ("tbng" to "11"),
        "MD1/Emblem Elspeth" to ("tmd1" to "4"),
        "CNS/Emblem Dack" to ("tcns" to "9"),
        "KTK/Emblem Sarkhan" to ("tktk" to "12"),
        "KTK/Emblem Sorin" to ("tktk" to "13"),
        "ORI/Emblem Chandra" to ("tori" to "14"),
        "ORI/Emblem Jace" to ("tori" to "12"),
        "ORI/Emblem Liliana" to ("tori" to "13"),
        "EMA/Emblem Dack" to ("tema" to "16"),
        "DDR/Emblem Nixilis" to ("ddr" to "76"),
        "MM3/Emblem Domri" to ("tmm3" to "21"),
        "CLB/Emblem Rowan Kenrith" to ("tclb" to "49"),
        "CLB/Emblem Will Kenrith" to ("tclb" to "50"),
        "2X2/Emblem Liliana" to ("t2x2" to "23"),
        "2X2/Emblem Wrenn" to ("t2x2" to "24"),
        "DMU/Emblem Ajani" to ("tdmu" to "25"),
        "DMU/Emblem Jaya" to ("tdmu" to "26"),
        "BRO/Emblem Saheeli" to ("tbro" to "12"),
        "ONE/Emblem Koth" to ("tone" to "13"),
        "MOM/Emblem Teferi" to ("tmom" to "22"),
        "MOM/Emblem Wrenn" to ("tmom" to "23"),
        "MOC/Emblem Elspeth" to ("tmoc" to "43"),
        "MOC/Emblem Teferi" to ("tmoc" to "44"),
        "DIS/Emblem Momir" to ("pmoa" to "61"),
        "CMM/Emblem Ajani" to ("tcmm" to "77"),
        "CMM/Emblem Chandra/1" to ("tcmm" to "78"),
        "CMM/Emblem Chandra/2" to ("tcmm" to "79"),
        "CMM/Emblem Daretti" to ("tcmm" to "51"),
        "CMM/Emblem Elspeth" to ("tcmm" to "80"),
        "CMM/Emblem Narset" to ("tcmm" to "81"),
        "CMM/Emblem Nixilis" to ("tcmm" to "52"),
        "CMM/Emblem Teferi" to ("tcmm" to "53"),
        "LCC/Emblem Sorin" to ("tlcc" to "16"),
        "RVR/Emblem Domri" to ("trvr" to "20"),
        "SCD/Emblem Nixilis" to ("tscd" to "26"),
        "SCD/Emblem Sarkhan" to ("tscd" to "27"),
        "MH3/Emblem Tamiyo" to ("tmh3" to "35"),
        "M3C/Emblem Garruk" to ("tm3c" to "27"),
        "M3C/Emblem Vivien" to ("tm3c" to "28"),
        "BLB/Emblem Ral" to ("tblb" to "30"),
        "DSK/Emblem Kaito" to ("tdsk" to "17"),
        "FDN/Emblem Kaito" to ("tfdn" to "24"),
        "FDN/Emblem Vivien" to ("tfdn" to "25"),
        "INR/Emblem Arlinn" to ("tinr" to "23"),
        "INR/Emblem Chandra" to ("tinr" to "24"),
        "INR/Emblem Jace" to ("tinr" to "25"),
        "INR/Emblem Tamiyo" to ("tinr" to "26"),
        "INR/Emblem Wrenn" to ("tinr" to "27"),
        "DFT/Emblem Chandra" to ("tdft" to "13"),
        "ACR/Emblem Capitoline Triad" to ("tacr" to "7"),
        "FIN/Emblem Sephiroth" to ("tfin" to "24"),
        "EOE/Emblem Tezzeret" to ("teoe" to "11"),
        "ECL/Emblem Oko" to ("tecl" to "12"),
        "SOS/Emblem Dellian" to ("tsos" to "13"),
    )
