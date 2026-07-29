package coms.seedm.model;

/**
 * Precedence (highest first) when a column happens to be classified more than one way:
 * CONSTRAINT > PARTIAL_MASK > MASK > PLAIN.
 * Constraint columns (PKs/FKs) are never touched so referential integrity survives
 * the masked export.
 */
public enum ColumnRole {
    CONSTRAINT,
    PARTIAL_MASK,
    MASK,
    PLAIN
}
