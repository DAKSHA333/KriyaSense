package com.kriyasense.assessment

enum class FormDriftState { NONE, WATCH, DRIFTING }
enum class FormDriftReason { ROM_DECLINING, TEMPO_SPEEDING_UP, MULTIPLE_SIGNALS }
data class FormDriftAnalysis(val state: FormDriftState=FormDriftState.NONE,val recentRepCount: Int=0,val reasons: Set<FormDriftReason> = emptySet())
/** Current-session only: four latest complete reps; three strictly directional changes trigger drift. */
object FormDrift {
    const val WINDOW_SIZE=4
    fun analyze(reps: List<Rep>): FormDriftAnalysis {
        val recent=reps.filter { it.complete }.takeLast(WINDOW_SIZE)
        if(recent.size<WINDOW_SIZE) return FormDriftAnalysis(recentRepCount=recent.size)
        fun descending(values: List<Double>)=values.zipWithNext().all { (a,b) -> b < a } && values.all { it.isFinite() }
        val rom=descending(recent.map { it.romPercentage })
        val tempo=descending(recent.map { it.durationSeconds })
        val reasons=buildSet { if(rom) add(FormDriftReason.ROM_DECLINING); if(tempo) add(FormDriftReason.TEMPO_SPEEDING_UP); if(rom&&tempo) add(FormDriftReason.MULTIPLE_SIGNALS) }
        return FormDriftAnalysis(if(reasons.isEmpty()) FormDriftState.NONE else FormDriftState.DRIFTING,recent.size,reasons)
    }
    fun message(analysis: FormDriftAnalysis)=when {
        FormDriftReason.MULTIPLE_SIGNALS in analysis.reasons -> "Your movement consistency is dropping. Consider a short pause."
        FormDriftReason.ROM_DECLINING in analysis.reasons -> "Your range is getting smaller. Focus on controlled full reps."
        else -> "Your reps are getting faster. Slow down and stay controlled."
    }
}
