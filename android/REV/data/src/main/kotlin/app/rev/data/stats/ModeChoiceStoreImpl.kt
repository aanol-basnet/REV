package app.rev.data.stats

import app.rev.data.local.stats.ModeChoiceDao
import app.rev.data.local.stats.ModeChoiceEntity
import app.rev.domain.modes.ModeChoice
import app.rev.domain.modes.ModeChoiceStore
import app.rev.domain.modes.ModeKeeper
import app.rev.domain.modes.ModeSituation

/** Your mode choices, kept on the phone (they work offline and stay private). */
class ModeChoiceStoreImpl(private val dao: ModeChoiceDao) : ModeChoiceStore {
    override suspend fun add(choice: ModeChoice) {
        val s = choice.situation
        dao.insert(ModeChoiceEntity(modeId = choice.modeId, output = s.output, outputName = s.outputName, hour = s.hour, weekday = s.weekday, network = s.network, charging = s.charging, at = choice.at))
        dao.trim(ModeKeeper.MAX_CHOICES)
    }

    override suspend fun recent(limit: Int): List<ModeChoice> = dao.recent(limit).map {
        ModeChoice(it.modeId, ModeSituation(it.output, it.outputName, it.hour, it.weekday, it.network, it.charging), it.at)
    }
}
