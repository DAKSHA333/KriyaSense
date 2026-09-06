package com.kriyasense.app

import android.content.Context

enum class ExperienceLevel { BEGINNER, INTERMEDIATE, ADVANCED }
enum class FitnessGoal { GENERAL_HEALTH, GET_IN_SHAPE, IMPROVE_FORM, BUILD_CONSISTENCY }
data class UserProfile(val heightCm: Double?=null,val weightKg: Double?=null,val experience: ExperienceLevel=ExperienceLevel.BEGINNER,val goal: FitnessGoal=FitnessGoal.GENERAL_HEALTH,val target: Int?=null) {
    val bmi: Double? get() = if(heightCm!=null && weightKg!=null && heightCm in 80.0..250.0 && weightKg in 20.0..350.0) weightKg/((heightCm/100)*(heightCm/100)) else null
    fun validationError()=when { heightCm!=null && heightCm !in 80.0..250.0 -> "Enter a height between 80 and 250 cm"; weightKg!=null && weightKg !in 20.0..350.0 -> "Enter a weight between 20 and 350 kg"; else -> null }
}
class SharedPreferencesUserProfile(context: Context) {
    private val preferences=context.applicationContext.getSharedPreferences("kriyasense_profile_v1",Context.MODE_PRIVATE)
    fun load()=UserProfile(preferences.getString("height",null)?.toDoubleOrNull(),preferences.getString("weight",null)?.toDoubleOrNull(),ExperienceLevel.valueOf(preferences.getString("experience",ExperienceLevel.BEGINNER.name)!!),FitnessGoal.valueOf(preferences.getString("goal",FitnessGoal.GENERAL_HEALTH.name)!!),preferences.getInt("target",0).takeIf { it>0 })
    fun save(profile: UserProfile) { preferences.edit().putString("height",profile.heightCm?.toString()).putString("weight",profile.weightKg?.toString()).putString("experience",profile.experience.name).putString("goal",profile.goal.name).putInt("target",profile.target?:0).apply() }
}
