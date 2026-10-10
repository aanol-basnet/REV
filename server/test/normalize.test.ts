import { describe, expect, it } from 'vitest';
import { artistKey, basicNormalize, titleKey } from '../src/library/normalize.js';

describe('normalization', () => {
  it('folds case, accents, punctuation and apostrophes', () => {
    expect(basicNormalize('Ænima')).toBe(basicNormalize('ænima'));
    expect(basicNormalize('Beyoncé')).toBe('beyonce');
    expect(basicNormalize('Don’t Stop Me Now')).toBe('dont stop me now');
    expect(basicNormalize('Guns N’ Roses')).toBe(basicNormalize("Guns N' Roses"));
    expect(basicNormalize('Simon & Garfunkel')).toBe('simon and garfunkel');
    expect(basicNormalize('10,000 Days')).toBe('10 000 days');
  });

  it('ignores edition qualifiers but keeps meaningful parentheses', () => {
    expect(titleKey('Lateralus (2021 Remaster)')).toBe(titleKey('Lateralus'));
    expect(titleKey('OK Computer - Deluxe Edition')).toBe(titleKey('OK Computer'));
    expect(titleKey('Ghost Reveries [Special Edition]')).toBe(titleKey('Ghost Reveries'));
    expect(titleKey('Alive! (Live)')).not.toBe(titleKey('Alive!'));
    expect(titleKey('Schism (Part 2)')).not.toBe(titleKey('Schism'));
  });

  it('strips featuring credits', () => {
    expect(titleKey('Stargazing (feat. Someone)')).toBe(titleKey('Stargazing'));
    expect(titleKey('Song ft. Other Artist')).toBe(titleKey('Song'));
    expect(titleKey('Song [Featuring X]')).toBe(titleKey('Song'));
  });

  it('treats a leading "The" in artist names as optional', () => {
    expect(artistKey('The Beatles')).toBe(artistKey('Beatles'));
    expect(artistKey('Tool')).toBe('tool');
  });
});
