import { describe, expect, it } from 'vitest';
import { ConfigError, loadConfig } from '../src/config.js';

describe('loadConfig', () => {
  it('applies defaults', () => {
    const c = loadConfig({});
    expect(c.port).toBe(4600);
    expect(c.host).toBe('127.0.0.1');
    expect(c.lidarr).toEqual({ searchOnRequest: true });
  });

  it('treats empty strings as unset', () => {
    expect(loadConfig({ LIDARR_URL: '', LIDARR_API_KEY: '' }).lidarr).toEqual({
      searchOnRequest: true,
    });
  });

  it('rejects invalid values without echoing them', () => {
    let error: unknown;
    try {
      loadConfig({ REV_PORT: 'abc', SLSKD_URL: 'not a url secret123' });
    } catch (e) {
      error = e;
    }
    expect(error).toBeInstanceOf(ConfigError);
    const message = (error as Error).message;
    expect(message).toContain('REV_PORT');
    expect(message).toContain('SLSKD_URL');
    expect(message).not.toContain('secret123');
  });
});
