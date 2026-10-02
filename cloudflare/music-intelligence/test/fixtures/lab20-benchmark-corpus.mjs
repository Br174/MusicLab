export const LAB20_BENCHMARK_CASES = [
  {
    id: 'benchmark-il-mondo',
    scenario: 'il-mondo',
    title: 'Il mondo',
    artist: 'Jimmy Fontana',
    mode: 'cover',
    payload: {
      original: {
        title: 'Il mondo',
        artist: 'Jimmy Fontana',
        credits: { composers: ['Jimmy Fontana'] },
      },
      versions: [
        {
          title: 'Il mondo',
          artist: 'Milva',
          category: 'cover',
          credits: { composers: ['Jimmy Fontana'] },
        },
      ],
    },
    expect: {
      minRetained: 1,
      mustNotRejectArtists: ['Milva'],
    },
  },
  {
    id: 'benchmark-few-covers',
    scenario: 'few-covers',
    title: 'Sparse Cover Fixture',
    artist: 'Original Artist',
    mode: 'cover',
    payload: {
      original: {
        title: 'Sparse Cover Fixture',
        artist: 'Original Artist',
        credits: {},
      },
      versions: [
        {
          title: 'Sparse Cover Fixture',
          artist: 'Rare Performer',
          category: 'cover',
          credits: {},
        },
      ],
    },
    expect: {
      minRetained: 1,
      mustNotRejectArtists: ['Rare Performer'],
      requireUncertain: true,
    },
  },
  {
    id: 'benchmark-international-english-hit',
    scenario: 'international-english-hit',
    title: 'Yesterday',
    artist: 'The Beatles',
    mode: 'cover',
    payload: {
      original: {
        title: 'Yesterday',
        artist: 'The Beatles',
        credits: { composers: ['Lennon/McCartney'] },
      },
      versions: [
        {
          title: 'Yesterday',
          artist: 'International Performer',
          category: 'cover',
          credits: { composers: ['Lennon/McCartney'] },
        },
      ],
    },
    expect: {
      minRetained: 1,
      mustNotRejectArtists: ['International Performer'],
    },
  },
  {
    id: 'benchmark-adapted-title-differs',
    scenario: 'adapted-title-differs',
    title: "Comme d'habitude",
    artist: 'Claude Francois',
    mode: 'cover',
    payload: {
      original: {
        title: "Comme d'habitude",
        artist: 'Claude Francois',
        credits: { composers: ['Claude Francois'] },
      },
      versions: [
        {
          title: 'My Way',
          artist: 'Adaptation Singer',
          category: 'adattamento',
          credits: { composers: ['Claude Francois'] },
        },
      ],
    },
    expect: {
      minRetained: 1,
      mustNotRejectArtists: ['Adaptation Singer'],
    },
  },
  {
    id: 'benchmark-many-originals',
    scenario: 'many-originals',
    title: 'Originals Benchmark',
    artist: 'Same Artist',
    mode: 'originals',
    payload: {
      original: {
        title: 'Originals Benchmark',
        artist: 'Same Artist',
        credits: { composers: ['Benchmark Composer'] },
      },
      versions: [
        {
          title: 'Originals Benchmark',
          artist: 'Same Artist',
          category: 'live',
          credits: { composers: ['Benchmark Composer'] },
        },
        {
          title: 'Originals Benchmark',
          artist: 'Same Artist',
          category: 'acoustic',
          credits: { composers: ['Benchmark Composer'] },
        },
        {
          title: 'Originals Benchmark',
          artist: 'Same Artist',
          category: 'remix',
          credits: { composers: ['Benchmark Composer'] },
        },
      ],
    },
    expect: {
      minRetained: 3,
      mustNotRejectArtists: ['Same Artist'],
    },
  },
  {
    id: 'benchmark-uncertain-case',
    scenario: 'uncertain-case',
    title: 'Uncertain Work',
    artist: 'Original Artist',
    mode: 'cover',
    payload: {
      original: {
        title: 'Uncertain Work',
        artist: 'Original Artist',
        credits: {},
      },
      versions: [
        {
          title: 'Different Adapted Title',
          artist: 'Possible Adapter',
          category: 'adattamento',
          credits: {},
        },
      ],
    },
    expect: {
      minRetained: 1,
      mustNotRejectArtists: ['Possible Adapter'],
      requireUncertain: true,
    },
  },
  {
    id: 'benchmark-poor-musicbrainz',
    scenario: 'poor-musicbrainz',
    title: 'Poor Metadata Fixture',
    artist: 'Obscure Artist',
    mode: 'cover',
    musicbrainzResult: {
      status: 'no_match',
      candidates: [],
    },
    payload: {
      original: {
        title: 'Poor Metadata Fixture',
        artist: 'Obscure Artist',
        credits: {},
      },
      versions: [
        {
          title: 'Poor Metadata Fixture',
          artist: 'Independent Performer',
          category: 'cover',
          credits: {},
        },
      ],
    },
    expect: {
      minRetained: 1,
      mustNotRejectArtists: ['Independent Performer'],
      requireUncertain: true,
    },
  },
];
