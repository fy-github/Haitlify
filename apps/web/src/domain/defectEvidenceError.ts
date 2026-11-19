export type DefectPersistenceCode = 'quota' | 'unavailable' | 'invalid-data' | 'corrupt-store' | 'incompatible-version' | 'missing-target' | 'verify-failed' | 'conflict' | 'unknown'
export type DefectPersistenceOutcome = 'confirmed-no-write' | 'uncertain-commit'
/** Safe message only. raw is for an explicit raw-text download, never a toast. */
export class DefectPersistenceError extends Error {
  readonly name = 'DefectPersistenceError'
  constructor(
    public readonly code: DefectPersistenceCode,
    public readonly outcome: DefectPersistenceOutcome,
    public readonly operation: string,
    message: string,
    public readonly path?: string,
    public readonly raw?: string
  ) { super(message) }
}
export const isDefectPersistenceError = (error: unknown): error is DefectPersistenceError => error instanceof DefectPersistenceError
