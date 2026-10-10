import { z } from 'zod';

/** Every non-2xx REV API response uses this shape. Messages are safe for display; no internals leak. */
export const ApiError = z.object({
  error: z.object({
    code: z.string(),
    message: z.string(),
  }),
});
export type ApiError = z.infer<typeof ApiError>;
