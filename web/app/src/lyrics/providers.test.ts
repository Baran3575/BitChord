import { describe, expect, it } from 'vitest';
import { betterLyricsCandidateUrls } from './providers';

describe('betterLyricsCandidateUrls', () => {
  it('builds descending specificity tiers without duplicates', () => {
    const urls = betterLyricsCandidateUrls('https://api.betterlyrics.org/getLyrics', {
      videoId: 'abc',
      title: 'Blinding Lights - 2020 Remaster',
      artist: 'The Weeknd & Daft Punk',
      durationMs: 200_000,
      album: 'After Hours',
      isrc: null,
    });

    expect(urls).toEqual([
      'https://api.betterlyrics.org/getLyrics?s=Blinding+Lights+-+2020+Remaster&a=The+Weeknd+%26+Daft+Punk&d=200&al=After+Hours',
      'https://api.betterlyrics.org/getLyrics?s=Blinding+Lights+-+2020+Remaster&a=The+Weeknd+%26+Daft+Punk&d=200',
      'https://api.betterlyrics.org/getLyrics?s=Blinding+Lights+-+2020+Remaster&a=The+Weeknd+%26+Daft+Punk',
      'https://api.betterlyrics.org/getLyrics?s=Blinding+Lights+-+2020+Remaster&a=The+Weeknd%2C+Daft+Punk&d=200',
      'https://api.betterlyrics.org/getLyrics?s=Blinding+Lights&a=The+Weeknd',
    ]);
  });
});
