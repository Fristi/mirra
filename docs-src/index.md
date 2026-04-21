---
layout: home

hero:
  name: "Mirra"
  text: "Mirror-test your tagless final algebras in Scala"
  tagline: Property-based testing using the test oracle pattern
  actions:
    - theme: brand
      text: Get Started
      link: /using-mirra/getting-started
    - theme: alt
      text: Introduction
      link: /introduction

features:
  - title: Test Oracle Pattern
    details: Verify that your real implementation agrees with a simple in-memory model — using property-based testing.
  - title: Tagless Final
    details: Works with any algebra defined in tagless final style via cats-tagless FunctorK and SemigroupalK.
  - title: Multi-Framework
    details: Out-of-the-box integrations for munit + cats-effect and ZIO Test.
  - title: Doobie & Skunk
    details: Database backends included; every property iteration runs inside a rolled-back transaction.
---
