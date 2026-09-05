/** Minimal type-only stub of @standard-schema/spec (Standard Schema V1). */
export interface StandardSchemaV1<Input = unknown, Output = Input> {
  readonly '~standard': StandardSchemaV1.Props<Input, Output>
}

export declare namespace StandardSchemaV1 {
  interface Props<Input = unknown, Output = Input> {
    version: 1
    vendor: string
    validate: (value: unknown) => Result<Output> | Promise<Result<Output>>
    types?: Types<Input, Output> | undefined
  }
  interface Result<Output> {
    readonly value?: Output
    readonly issues?: ReadonlyArray<Issue>
  }
  interface Issue {
    readonly message: string
    readonly path?: ReadonlyArray<PropertyKey | PathSegment> | undefined
  }
  interface PathSegment {
    readonly key: PropertyKey
  }
  type Types<Input, Output> = {
    readonly input: Input
    readonly output: Output
  }
}
