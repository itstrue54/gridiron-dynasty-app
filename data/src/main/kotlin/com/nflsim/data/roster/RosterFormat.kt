package com.nflsim.data.roster

import com.nflsim.engine.model.Archetype
import com.nflsim.engine.model.PositionGroup
import com.nflsim.engine.model.DevCurve
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.RatingId

/**
 * How a roster CSV maps onto the model.
 *
 * The importer is forgiving on purpose. Ratings dumps come from spreadsheets,
 * game exports and hand-typed lists, and they all name things slightly
 * differently. Anything not recognised is reported, never silently dropped.
 */
object RosterFormat {

    /** Column names the importer understands, normalised. */
    object Columns {
        val TEAM = setOf("team", "teamabbrev", "club", "franchise")
        val FIRST = setOf("first", "firstname", "givenname")
        val LAST = setOf("last", "lastname", "surname", "familyname")
        val FULL = setOf("name", "player", "playername", "fullname")
        val POSITION = setOf("pos", "position")
        val ARCHETYPE = setOf("archetype", "type", "style", "playertype")
        val OVERALL = setOf("ovr", "overall", "rating", "rtg")
        val JERSEY = setOf("num", "no", "number", "jersey", "jerseynumber")
        val AGE = setOf("age")
        val BIRTH_YEAR = setOf("birthyear", "born", "yearborn")
        val HEIGHT_IN = setOf("height", "heightin", "heightinches", "ht")
        val WEIGHT_LB = setOf("weight", "weightlb", "weightlbs", "wt")
        val COLLEGE = setOf("college", "school", "university")
        val DEV_CURVE = setOf("dev", "devtrait", "development", "devcurve")
    }

    /**
     * Position spellings seen in the wild, mapped onto the model's 18.
     * Ambiguous ones (a generic tackle, a generic guard) resolve to the left
     * side; the importer notes it so nobody is surprised.
     */
    val POSITION_ALIASES: Map<String, Position> = buildMap {
        Position.entries.forEach { put(it.name.lowercase(), it) }
        put("qb", Position.QB); put("quarterback", Position.QB)
        put("rb", Position.RB); put("hb", Position.RB); put("tailback", Position.RB)
        put("runningback", Position.RB)
        put("fb", Position.FB); put("fullback", Position.FB)
        put("wr", Position.WR); put("widereceiver", Position.WR); put("wo", Position.WR)
        put("te", Position.TE); put("tightend", Position.TE)
        put("lt", Position.LT); put("leftackle", Position.LT); put("lefttackle", Position.LT)
        put("rt", Position.RT); put("righttackle", Position.RT)
        put("t", Position.LT); put("ot", Position.LT); put("tackle", Position.LT)
        put("lg", Position.LG); put("leftguard", Position.LG)
        put("rg", Position.RG); put("rightguard", Position.RG)
        put("g", Position.LG); put("og", Position.LG); put("guard", Position.LG)
        put("c", Position.C); put("center", Position.C); put("centre", Position.C)
        put("edge", Position.EDGE); put("de", Position.EDGE); put("olb", Position.EDGE)
        put("rush", Position.EDGE); put("leo", Position.EDGE); put("defensiveend", Position.EDGE)
        put("dt", Position.DT); put("nt", Position.DT); put("nose", Position.DT)
        put("dl", Position.DT); put("defensivetackle", Position.DT)
        put("lb", Position.LB); put("ilb", Position.LB); put("mlb", Position.LB)
        put("linebacker", Position.LB); put("will", Position.LB); put("mike", Position.LB)
        put("sam", Position.LB)
        put("cb", Position.CB); put("db", Position.CB); put("corner", Position.CB)
        put("cornerback", Position.CB); put("nb", Position.CB); put("nickel", Position.CB)
        put("s", Position.S); put("fs", Position.S); put("ss", Position.S)
        put("safety", Position.S); put("saf", Position.S)
        put("k", Position.K); put("pk", Position.K); put("kicker", Position.K)
        put("p", Position.P); put("punter", Position.P)
        put("ls", Position.LS); put("longsnapper", Position.LS); put("snapper", Position.LS)
    }

    /** Spellings that lose information - worth telling the user about. */
    val AMBIGUOUS_POSITIONS = setOf("t", "ot", "tackle", "g", "og", "guard", "dl", "db", "olb")

    /** Rating column spellings. Falls back to the enum name with separators stripped. */
    val RATING_ALIASES: Map<String, RatingId> = buildMap {
        RatingId.entries.forEach { put(it.name.lowercase().replace("_", ""), it) }
        put("spd", RatingId.SPEED); put("acc", RatingId.ACCELERATION)
        put("agi", RatingId.AGILITY); put("str", RatingId.STRENGTH)
        put("jmp", RatingId.JUMPING); put("sta", RatingId.STAMINA)
        put("inj", RatingId.INJURY_RESIST); put("tgh", RatingId.TOUGHNESS)
        put("awr", RatingId.AWARENESS); put("prc", RatingId.PLAY_RECOGNITION)
        put("dis", RatingId.DISCIPLINE)
        put("thp", RatingId.THROW_POWER)
        put("tas", RatingId.THROW_ACC_SHORT); put("shortaccuracy", RatingId.THROW_ACC_SHORT)
        put("tam", RatingId.THROW_ACC_MID); put("mediumaccuracy", RatingId.THROW_ACC_MID)
        put("tad", RatingId.THROW_ACC_DEEP); put("deepaccuracy", RatingId.THROW_ACC_DEEP)
        put("tor", RatingId.THROW_ON_RUN); put("tup", RatingId.THROW_UNDER_PRESSURE)
        put("bsk", RatingId.BREAK_SACK); put("pac", RatingId.PLAY_ACTION)
        put("scr", RatingId.SCRAMBLING)
        put("car", RatingId.CARRYING); put("bcv", RatingId.VISION)
        put("btk", RatingId.BREAK_TACKLE); put("trk", RatingId.TRUCKING)
        put("elu", RatingId.ELUSIVENESS); put("jkm", RatingId.JUKE_MOVE)
        put("spm", RatingId.SPIN_MOVE); put("sfa", RatingId.STIFF_ARM)
        put("bcvision", RatingId.VISION)
        put("cth", RatingId.CATCHING); put("cit", RatingId.CATCH_IN_TRAFFIC)
        put("spc", RatingId.SPECTACULAR_CATCH); put("srr", RatingId.ROUTE_SHORT)
        put("mrr", RatingId.ROUTE_MID); put("drr", RatingId.ROUTE_DEEP)
        put("rls", RatingId.RELEASE)
        put("rbk", RatingId.RUN_BLOCK); put("pbk", RatingId.PASS_BLOCK)
        put("ibl", RatingId.IMPACT_BLOCK); put("lbk", RatingId.LEAD_BLOCK)
        put("rbp", RatingId.RUN_BLOCK_POWER); put("rbf", RatingId.RUN_BLOCK_FINESSE)
        put("pbp", RatingId.PASS_BLOCK_POWER); put("pbf", RatingId.PASS_BLOCK_FINESSE)
        put("pmv", RatingId.POWER_MOVES); put("fmv", RatingId.FINESSE_MOVES)
        put("bsh", RatingId.BLOCK_SHEDDING); put("pur", RatingId.PURSUIT)
        put("tak", RatingId.TACKLE); put("tkl", RatingId.TACKLE)
        put("pow", RatingId.HIT_POWER); put("hitpow", RatingId.HIT_POWER)
        put("mcv", RatingId.MAN_COVERAGE); put("zcv", RatingId.ZONE_COVERAGE)
        put("prs", RatingId.PRESS)
        put("kpw", RatingId.KICK_POWER); put("kac", RatingId.KICK_ACCURACY)
        put("ppw", RatingId.PUNT_POWER); put("pac2", RatingId.PUNT_ACCURACY)
        put("puntpower", RatingId.PUNT_POWER); put("puntaccuracy", RatingId.PUNT_ACCURACY)
    }

    val ARCHETYPE_ALIASES: Map<PositionGroup, Map<String, Archetype>> =
        Archetype.entries.groupBy { it.group }.mapValues { (_, list) ->
            buildMap {
                list.forEach {
                    put(it.label.lowercase().replace(Regex("[^a-z0-9]"), ""), it)
                    put(it.name.lowercase().replace("_", ""), it)
                }
            }
        }

    val DEV_ALIASES: Map<String, DevCurve> = buildMap {
        DevCurve.entries.forEach {
            put(it.name.lowercase().replace("_", ""), it)
            put(it.label.lowercase().replace("-", ""), it)
        }
        put("star", DevCurve.QUICK)
        put("elite", DevCurve.SUPERSTAR)
        put("xfactor", DevCurve.X_FACTOR)
        put("normal", DevCurve.NORMAL)
    }

    /** Strips case, spaces, underscores, dots and dashes so headers match loosely. */
    fun normalise(header: String): String =
        header.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
}
