export const DAY_MS = 86_400_000;
const HOUR_MS = 3_600_000;

/** A calendar date, month 1–12. */
export interface LocalDate {
  year: number;
  month: number;
  day: number;
}

/**
 * Calendar arithmetic in an IANA time zone with only `Intl`: local dates, hours and weekdays of
 * instants, and the instant a local day starts. UTC offsets are cached per hour, which keeps
 * reports over years of listens cheap.
 */
export class Zone {
  private readonly format: Intl.DateTimeFormat;
  private readonly offsets = new Map<number, number>();

  constructor(readonly name: string) {
    this.format = new Intl.DateTimeFormat('en-US', {
      timeZone: name,
      hourCycle: 'h23',
      year: 'numeric',
      month: 'numeric',
      day: 'numeric',
      hour: 'numeric',
      minute: 'numeric',
      second: 'numeric',
    });
  }

  /** Local time minus UTC at [ms], in ms. */
  offset(ms: number): number {
    const hour = Math.floor(ms / HOUR_MS);
    const cached = this.offsets.get(hour);
    if (cached !== undefined) return cached;
    const at = hour * HOUR_MS;
    const parts = { year: 1970, month: 1, day: 1, hour: 0, minute: 0, second: 0 };
    for (const p of this.format.formatToParts(at)) {
      if (p.type in parts) parts[p.type as keyof typeof parts] = Number(p.value);
    }
    const local = Date.UTC(
      parts.year,
      parts.month - 1,
      parts.day,
      parts.hour,
      parts.minute,
      parts.second,
    );
    const offset = local - at;
    this.offsets.set(hour, offset);
    return offset;
  }

  /** Days since 1970-01-01 of the local date at [ms]; consecutive days differ by one. */
  dayNumber(ms: number): number {
    return Math.floor((ms + this.offset(ms)) / DAY_MS);
  }

  hour(ms: number): number {
    return new Date(ms + this.offset(ms)).getUTCHours();
  }

  /** 0 = Monday … 6 = Sunday. */
  weekday(ms: number): number {
    return (new Date(ms + this.offset(ms)).getUTCDay() + 6) % 7;
  }

  date(ms: number): LocalDate {
    const d = new Date(ms + this.offset(ms));
    return { year: d.getUTCFullYear(), month: d.getUTCMonth() + 1, day: d.getUTCDate() };
  }

  /** The instant the local date starts. Months and days out of range roll over (Date.UTC rules). */
  startOf(year: number, month: number, day: number): number {
    const wall = Date.UTC(year, month - 1, day);
    // Two passes settle the offset around DST changes.
    let t = wall - this.offset(wall);
    t = wall - this.offset(t);
    return t;
  }

  /** The instant a day number (see [dayNumber]) starts. */
  startOfDayNumber(n: number): number {
    const d = new Date(n * DAY_MS);
    return this.startOf(d.getUTCFullYear(), d.getUTCMonth() + 1, d.getUTCDate());
  }
}
