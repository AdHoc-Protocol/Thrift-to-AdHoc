# Thrift-to-AdHoc — Apache Thrift IDL → AdHoc protocol description

> One of the [**converters to AdHoc protocol**](https://github.com/AdHoc-Protocol#converters-to-adhoc-protocol).
> Take a protocol you already have, get an [AdHoc](https://github.com/AdHoc-Protocol/AdHoc-protocol) description,
> open it in the Observer. The result is a starting point you refine by hand, not a finished protocol.

Converts Apache Thrift interface-definition files (`.thrift`) into
[AdHoc](https://github.com/AdHoc-Protocol) protocol-description `.cs` files, ready for AdHocAgent to generate
Java / C# / C++ / TypeScript / Go / Rust code from.

The converter is a single self-contained Java class: a hand-written tokenizer and recursive-descent parser for
the Thrift grammar, plus an emitter. No third-party dependencies, no Thrift compiler required.

This project is fully isolated: it carries its own copy of the emitter helpers
(`src/org/unirail/adhoc/`) and its own `validate.sh`, and references nothing outside its own folder.

## Links

- Thrift IDL specification: https://thrift.apache.org/docs/idl
- Thrift types overview: https://thrift.apache.org/docs/types
- Apache Thrift repository: https://github.com/apache/thrift

Sample schemas fetched by `fetch-samples.sh` (all verified to download):

| File | Source |
|:--|:--|
| `tutorial.thrift`, `shared.thrift` | https://github.com/apache/thrift/blob/master/tutorial/tutorial.thrift , https://github.com/apache/thrift/blob/master/tutorial/shared.thrift |
| `ThriftTest.thrift` | https://github.com/apache/thrift/blob/master/test/ThriftTest.thrift |
| `fb303.thrift` | https://github.com/apache/thrift/blob/master/contrib/fb303/if/fb303.thrift |
| `jaeger.thrift` | https://github.com/jaegertracing/jaeger-idl/blob/main/thrift/jaeger.thrift |
| `parquet.thrift` | https://github.com/apache/parquet-format/blob/master/src/main/thrift/parquet.thrift |
| `cassandra.thrift` | https://github.com/apache/cassandra/blob/cassandra-2.2/interface/cassandra.thrift |
| `hive_metastore.thrift` | https://github.com/apache/hive/blob/master/standalone-metastore/metastore-common/src/main/thrift/hive_metastore.thrift |

## Before and after

`samples/cassandra.thrift` (945 lines) — [source](samples/cassandra.thrift) → [result](AdHoc/cassandra.cs)

```thrift
exception InvalidRequestException {
    1: required string why
}

struct ColumnPath {
    3: required string column_family,
    4: optional binary super_column,
    5: optional binary column,
}

service Cassandra {
  # auth methods
  void login(1: required AuthenticationRequest auth_request) throws (1:AuthenticationException authnx, 2:AuthorizationException authzx),

  # set keyspace
  void set_keyspace(1: required string keyspace) throws (1:InvalidRequestException ire),

  # retrieval methods
  ColumnOrSuperColumn get(1:required binary key,
                          2:required ColumnPath column_path,
                          3:required ConsistencyLevel consistency_level=ConsistencyLevel.ONE)
                      throws (1:InvalidRequestException ire, 2:NotFoundException nfe, 3:UnavailableException ue, 4:TimedOutException te),
  // … 42 more methods …
}
```

```csharp
public class InvalidRequestException {
    string why; // 1:
}

public class ColumnPath {
    string column_family; // 3:
    Binary[,,] super_column; // 4:
    Binary[,,] column; // 5:
}

public class Cassandra_get_Args {
    Binary[,,] key; // 1:
    ColumnPath column_path; // 2:
    ConsistencyLevel consistency_level; // 3:
    public const int consistency_level_default = 1; // ConsistencyLevel.ONE
}

public class Cassandra_get_Result {
    ColumnOrSuperColumn success; // 0:
}
// … the connection, where every method of the service becomes one line …
interface Cassandra {
    (L____________, Cassandra_login_Result, AuthenticationException, AuthorizationException) login(Cassandra_login_Args req);
    (L____________, Cassandra_set_keyspace_Result, InvalidRequestException) set_keyspace(Cassandra_set_keyspace_Args req);
    (L____________, Cassandra_get_Result, InvalidRequestException, NotFoundException, UnavailableException, TimedOutException) get(Cassandra_get_Args req);
    // … 42 more …
}
```

## Commands

```bash
./fetch-samples.sh          # download the sample .thrift files into samples/
./build.sh                  # compile src/ into out/ and convert samples/ into AdHoc/
./validate.sh AdHoc         # AdHocAgent parse-only check of every generated .cs (nothing is uploaded)

# direct use
javac -encoding UTF-8 --release 17 -d out src/org/unirail/adhoc/*.java src/org/unirail/*.java
java -cp out org.unirail.Thrift2AdHoc <file.thrift | folder> [output folder]   # output defaults to ./AdHoc
```

One `.cs` is produced per top-level `.thrift`. Files reached through `include` are merged into the same
descriptor as a nested `public struct <fileBase> { … }`, so Thrift's qualified names (`shared.SharedStruct`)
survive as C# paths. An `include "share/fb303/if/fb303.thrift"` is resolved by file name next to the includer,
which is how the samples are laid out.

## Mapping

| Thrift | AdHoc | Notes |
|:--|:--|:--|
| `struct` | `public class` (pack) | listed in the Packs Inventory |
| `union` | `public class` with every field optional | a doc note records that exactly one field is set |
| `exception` | `public class` | used as an alternative RPC result |
| `enum` | `enum` (`: long` when a value exceeds `int`) | fewer than two members → `struct` constants container, since AdHoc rejects such enums |
| `typedef` | `public class X { <type> TYPEDEF; }` | AdHoc type alias |
| `const` | `public const` inside `struct Consts` | see *Dropped / approximated* |
| field id `3:` | trailing comment `// 3:` | for finding the field in the `.thrift`; nothing in AdHoc reads it |
| `required` | dropped | an AdHoc value type is mandatory unless it is `T?`; reference types are optional by nature |
| `optional` | `T?` for value types | reference types are optional by nature in AdHoc |
| field default | `public const <type> <field>_default = …;` in the same pack | same rules as `const`: enum members become their number, containers are skipped with a comment |
| annotation `(k="v")` | `[Annotation("k","v")]` | repeatable; also captured from type annotations; not read by AdHoc |
| `bool` `i8`/`byte` `double` `string` | `bool` `sbyte` `double` `string` | no varint attribute: a one-byte type has nothing to drop, and floats and strings are not integers |
| `i16` `i32` `i64` | `[X] short` `[X] int` `[X] long` | Thrift Compact chose **ZigZag varint** for these, which is a statement about the data - see *Number physics* below |
| `binary` | `Binary[,,]` | not an integer, so no varint attribute |
| `uuid` | `[D(16)] Binary[]` | |
| `list<T>` | `T[,,]` | bound comes from `_DefaultMaxLengthOf`, not a per-field `[D]` |
| `set<T>` | `Set<T>` | as above; an `i16/i32/i64` element adds `[Key: X]` |
| `map<K,V>` | `Map<K,V>` | as above; `[Key: X]` / `[Val: X]` per side |
| `service` method | `<Service>_<method>_Args` + `_Result` packs, and `(L____________, Result, Exc…) method(Args req);` inside `interface ClientServer : Connects<Client, Server>` | client calls, server replies |
| `void` method | `_Result` pack with no fields | |
| `oneway` method | `[l____________<…_Args>] struct <Service>_<method> { }` | fire-and-forget state of the connection |
| `throws (1: E e)` | extra result types in the RPC tuple | |
| `service B extends A` | A's methods repeated in B's RPC group | Thrift inheritance is flattened; A's packs stay in A |
| empty `struct` as a field type | `bool` | AdHoc carries an empty pack as a presence flag |

Two hosts are emitted, `Client` (Left) and `Server` (Right), each requesting all six target languages. A source
with no `service` gets a single bidirectional state over every pack instead of RPC declarations.

## Number physics: why the integers carry `[X]`

AdHoc asks a schema to say **where a number's values actually sit**, and lays out the wire from that. The
source's own storage is no argument either way, because AdHoc builds its own frame; what carries over is what
the source reveals about the data.

Thrift reveals it once: the **Compact protocol** encodes `i16`, `i32` and `i64` as ZigZag varint. That was a
deliberate bet that such fields hold small magnitudes of either sign, and it is exactly what AdHoc's `[X]` says,
so those fields are emitted as `[X] short` / `[X] int` / `[X] long`. The other scalars carry no attribute for
reasons of their own: `i8` and `bool` already fit in a byte, so there are no leading zero groups to drop, and
`double`, `string` and `binary` are not integers at all.

On a collection the attribute applies to the elements; a `Set` key and a `Map` key / value take the `[Key: X]`
and `[Val: X]` forms, each in its own bracket. A `typedef` of an integer carries the attribute on the alias, and
the agent propagates it to every field that uses it.

### Check the arithmetic

Varint spends one bit in every eight on a continuation flag, so it pays only while the value stays near its base:

| typical distance from zero | varint bytes | against a fixed `int` |
|:--|--:|:--|
| 0 … 127 | 1 | saves 3 bytes |
| 128 … 16 383 | 2 | saves 2 |
| 16 384 … 2 097 151 | 3 | saves 1 |
| 2 097 152 … 268 435 455 | 4 | breaks even |
| past 268 435 455 | 5+ | **loses, on every packet, forever** |

So an epoch-millisecond timestamp, a monotonic id that has grown past ~268 million, a hash or a checksum is a
**loss** with `[X]` — not because Thrift stored it one way or another, but because its values are systematically
large or uniformly spread. Delete the attribute there and the field returns to fixed width.

Where a field's name, documentation or annotation gives that away, the converter says so **on the field**, and
leaves the decision to whoever knows the data:

```csharp
// physics: looks like a wall-clock time, always far above 2^28 - [X] costs a byte per packet; drop it, or model the instant as DateTime
[X] long create_time; // 3:

// physics: a counter or an offset - it only grows, so [A] (clustered at the floor) fits better than [X]
[X] int key_seq; // 4:
```

The four notes it can emit cover wall-clock times, counters and offsets, identifiers, and unpredictable values
(hashes, checksums, uuids, nonces). No attribute is ever invented from a guess — only `[X]`, which Thrift itself
declared, is emitted; everything else is a comment. Across the shipped samples 405 fields carry the attribute and
133 carry a physics note.

## Time

Thrift has **no temporal type**: timestamps and durations are plain `i64` fields by convention, with the unit
stated only in prose. Nothing is mapped to AdHoc's `DateTime`, `DateTimeDef`, `Duration` or `TimeSpanDef`,
because no automatic rule could tell an epoch-millisecond timestamp from any other `i64`. Where you recognise one
in the generated file, replacing `[X] long` with `DateTime` or a `class X : Duration { max; precision; }` alias
is a one-line edit and is worth making.

## Dropped / approximated

- **`const` of a container or struct type** is skipped with a comment holding the original text; C# has no literal
  form for it. A `const list<T>`/`set<T>` of scalars becomes a `static T[]`.
- **`const` of an enum type** becomes an `int` holding the member's numeric value, with the member named in a
  comment: AdHoc constants cannot have an enum type.
- **`const i8` / `const i16`** widen to `int` (noted in a trailing comment). AdHocAgent crashes on narrow signed
  constants.
- **Deeply nested containers.** AdHoc allows two array levels on a plain field, one on a `Set`/`Map` head, and no
  `Set`/`Map` inside a `Set`/`Map` slot. Anything deeper is wrapped in a generated one-field pack named
  `Wrap_<shape>` (for example `Map<Set<i32>, …>` becomes `Map<Wrap_Set_i32, …>`). Nothing is lost; the wire gains
  one nesting level.
- **`senum`** (deprecated in Thrift) is parsed and skipped; a `// dropped from <file>: senum <name>` comment is
  emitted at the top of the generated interface so the loss is visible in the file, not only here.
- **Namespaces** are recorded as header comments only; the AdHoc namespace is `org.thrift` and the project
  interface is named after the file.
- **`required`** is dropped, **field ids** become trailing `// N:` comments and **defaults** become `<field>_default`
  constants: none of them changes the encoding, and only what AdHoc acts on is expressed as an attribute. AdHoc is
  not Thrift wire-compatible.
- A type named like an `org.unirail.Meta` member (`Map`, `Set`, `Binary`, `Host`, …) is renamed with a `Pack`
  suffix so it cannot shadow the meta type.

## Validation result

`./validate.sh AdHoc` — all eight descriptors pass AdHocAgent's local parse-only check (exit 0, no errors or
warnings):

| Descriptor | Packs | Enums | Services | RPC | oneway | Result |
|:--|--:|--:|--:|--:|--:|:--|
| `ThriftTest.cs` | 75 | 1 | 2 | 23 | 1 | OK |
| `cassandra.cs` | 128 | 5 | 1 | 45 | 0 | OK |
| `fb303.cs` | 24 | 1 | 1 | 11 | 2 | OK |
| `hive_metastore.cs` | 935 | 27 | 2 | 309 | 2 | OK |
| `jaeger.cs` | 10 | 2 | 1 | 1 | 0 | OK |
| `parquet.cs` | 61 | 8 | 0 | 0 | 0 | OK |
| `shared.cs` | 3 | 0 | 1 | 1 | 0 | OK |
| `tutorial.cs` | 12 | 1 | 2 | 5 | 1 | OK |

`hive_metastore.cs` is the stress case: it includes `fb303.thrift`, and its `ThriftHiveMetastore` service extends
`fb303.FacebookService`, so inherited methods (including two `oneway` ones) are flattened into one RPC group
while their packs stay in the `fb303` container.

## Limitations

- The parser covers the IDL as the samples use it. `cpp_include`, `php_namespace`, `xsd_*` markers and
  `senum` are recognised and skipped rather than represented.
- Constant expressions are literals only; Thrift has no arithmetic in constants, so this is not a restriction in
  practice, but a reference to another constant is emitted as a bare path and is not checked.
- Service inheritance is flattened per service. If a base and a derived service declare the same method name, the
  derived one wins and the base one is not repeated.
- Thrift declares no size bounds, so every file opens with `enum _DefaultMaxLengthOf { Arrays = 65_535, Maps =
  65_535, Sets = 65_535, Strings = 65_535 }` and carries no per-field `[D]` cap. Add `[D(+N)]` to a field whose
  real bound you know - the permissive default is a starting point, not a measurement.
