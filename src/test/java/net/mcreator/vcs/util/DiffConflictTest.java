package net.mcreator.vcs.util;

import net.mcreator.util.diff.ListDiff;
import net.mcreator.vcs.util.diff.DiffResultToBaseConflictFinder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiffConflictTest {
	@Test void modifiedSidesUseCurrentValuesRatherThanBaseInstances() {
		Value base = new Value("item", 0);
		Value local = new Value("item", 1);
		Value remote = new Value("item", 2);
		var conflicts = DiffResultToBaseConflictFinder.findConflicts(
				ListDiff.getListDiff(List.of(base), List.of(local)), ListDiff.getListDiff(List.of(base), List.of(remote)),
				List.of(local), List.of(remote));
		assertEquals(1, conflicts.size());
		var conflict = conflicts.iterator().next();
		assertSame(local, conflict.getLocal());
		assertSame(remote, conflict.getRemote());
	}

	@Test void deleteModifyConflictKeepsTheUpdatedSurvivingSide() {
		Value base = new Value("item", 0);
		Value remote = new Value("item", 2);
		var conflict = DiffResultToBaseConflictFinder.findConflicts(
				ListDiff.getListDiff(List.of(base), List.<Value>of()), ListDiff.getListDiff(List.of(base), List.of(remote)),
				List.<Value>of(), List.of(remote)).iterator().next();
		assertSame(base, conflict.getLocal());
		assertSame(remote, conflict.getRemote());
	}

	private record Value(String name, int value) {
		@Override public boolean equals(Object other) { return other instanceof Value v && name.equals(v.name); }
		@Override public int hashCode() { return name.hashCode(); }
	}
}
