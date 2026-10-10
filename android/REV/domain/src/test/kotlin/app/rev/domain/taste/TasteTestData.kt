package app.rev.domain.taste

import app.rev.domain.model.Track

internal fun song(
    id: String,
    artist: String = "A",
    album: String = "al-$id",
    genre: String? = "Rock",
    year: Int? = 2000,
    starred: Boolean = false,
    n: Int = 1,
) = Track(
    id = id, title = id, album = album, albumId = album, artist = artist, artistId = "ar-$artist",
    trackNumber = n, discNumber = 1, year = year, genre = genre, coverArtId = null, durationMs = 200_000,
    starred = starred, format = null, bitRateKbps = null, sampleRateHz = null, bitDepth = null, sizeBytes = null,
)
