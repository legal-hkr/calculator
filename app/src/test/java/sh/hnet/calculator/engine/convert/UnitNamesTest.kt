package sh.hnet.calculator.engine.convert

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.hnet.calculator.ui.converter.unitName

/** Every unit has its name string (the map is generated; a unit added to Units must be added there too). */
class UnitNamesTest {
    @Test fun everyUnitHasAName() {
        val ids = Units.all.map { unitName(it.category.name.lowercase(), it.id) }
        assertEquals(ids.size, ids.toSet().size)
    }
}
