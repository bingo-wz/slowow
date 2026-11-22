package app.manman.teacher.data

import app.manman.teacher.domain.*
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

object ForestRecords {
    const val PROFILE = "forest_profile_"
    const val INTERACTION = "forest_day_"

    fun profileId(student: String) = PROFILE + id(student)
    fun interactionId(student: String, day: LocalDate) = INTERACTION + id("$student:$day")

    private fun id(value: String) = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()

    fun encode(value: ForestProfile): String = JSONObject()
        .put("type", "forest_profile").put("studentId", value.studentId)
        .put("animal", value.animal?.name ?: JSONObject.NULL).put("group", value.group.name)
        .put("support", value.support.name).put("note", value.note)
        .put("watchSince", value.watchSince?.toString() ?: JSONObject.NULL).toString()

    fun profile(record: LocalRecord): ForestProfile = JSONObject(record.payload).let {
        ForestProfile(it.getString("studentId"), if (it.isNull("animal")) null else ForestAnimal.valueOf(it.getString("animal")),
            ForestGroup.valueOf(it.getString("group")), ForestSupport.valueOf(it.getString("support")),
            it.optString("note"), if (it.isNull("watchSince")) null else LocalDate.parse(it.getString("watchSince")))
    }

    fun encode(value: ForestInteraction): String = JSONObject()
        .put("type", "forest_day").put("studentId", value.studentId).put("day", value.day.toString()).toString()

    fun interaction(record: LocalRecord): ForestInteraction = JSONObject(record.payload).let {
        ForestInteraction(it.getString("studentId"), LocalDate.parse(it.getString("day")))
    }
}
