import type { z } from 'zod';
import { HttpError } from './errors.js';

/** Parses untrusted input, turning schema failures into a 400 that names the offending fields. */
export function parseInput<T extends z.ZodType>(schema: T, input: unknown): z.infer<T> {
  const result = schema.safeParse(input);
  if (result.success) return result.data;
  const fields = [...new Set(result.error.issues.map((i) => i.path.join('.') || '(body)'))];
  throw new HttpError(400, 'VALIDATION_ERROR', `Invalid request: ${fields.join(', ')}`);
}
