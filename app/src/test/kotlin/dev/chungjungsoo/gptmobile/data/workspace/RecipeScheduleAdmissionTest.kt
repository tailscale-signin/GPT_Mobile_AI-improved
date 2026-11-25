package dev.chungjungsoo.gptmobile.data.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeScheduleAdmissionTest {
    private val recipe = TaskRecipe("Summarize", "profile", scheduled = true, intervalHours = 2, lastQueuedAt = 5000L)
    private val dueAt = recipe.lastQueuedAt + 7_200_000L

    @Test fun dueRecipeCanQueueAtTheConfiguredInterval() {
        assertTrue(recipe.isDue(dueAt))
        assertFalse(recipe.isDue(dueAt - 1))
    }

    @Test fun disabledRecipeAndFutureCheckpointCannotQueue() {
        assertFalse(recipe.copy(scheduled = false).isDue(dueAt))
        assertFalse(recipe.isDue(0))
    }
}
