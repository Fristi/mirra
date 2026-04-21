import { defineConfig } from 'vitepress'

export default defineConfig({
  base: '/mirra',
  title: 'Mirra',
  description: 'Mirror-test your tagless final algebras in Scala',
  themeConfig: {
    nav: [
      { text: 'Guide', link: '/using-mirra/getting-started' },
      { text: 'GitHub', link: 'https://github.com/Fristi/mirra' },
    ],
    sidebar: [
      {
        text: 'Introduction',
        items: [
          { text: 'What is Mirra?', link: '/introduction' },
        ],
      },
      {
        text: 'Using Mirra',
        items: [
          { text: 'Getting Started', link: '/using-mirra/getting-started' },
          { text: 'Combinators', link: '/using-mirra/combinators' },
          { text: 'Composing Repositories', link: '/using-mirra/composing-repositories' },
        ],
      },
      {
        text: 'Testing',
        items: [
          { text: 'munit + cats-effect', link: '/testing/munit' },
          { text: 'ZIO Test', link: '/testing/zio-test' },
          { text: 'Doobie', link: '/testing/doobie' },
          { text: 'Skunk', link: '/testing/skunk' },
        ],
      },
      {
        text: 'In-memory',
        items: [
          { text: 'cats-effect', link: '/in-memory/cats-effect' },
          { text: 'ZIO', link: '/in-memory/zio' },
        ],
      },
    ],
    socialLinks: [
      { icon: 'github', link: 'https://github.com/Fristi/mirra' },
    ],
  },
})
