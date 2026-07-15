# LoomSIP Development Conventions

## Javadoc

All new public or protected classes, interfaces, records, enums, constructors, fields, and methods must include Javadoc.

Javadoc should describe the API contract rather than restate the declaration. Include the following details when they apply:

- The responsibility and layer boundary of the type.
- Mutability and ownership of data, especially arrays and Netty reference-counted objects.
- Threading and callback execution rules.
- Valid parameter ranges and null handling.
- Return value semantics, including empty values and asynchronous completion.
- Checked and relevant runtime exceptions.
- Lifecycle restrictions and valid object states.

Use `@param`, `@return`, and `@throws` for public methods where they add contract information. Record components should be documented with `@param` tags on the record declaration.

Package-private implementation types should also have Javadoc when their responsibility, concurrency behavior, resource ownership, or protocol behavior is not obvious from the code.

Standard overrides such as `equals`, `hashCode`, and `toString` do not need duplicate Javadoc when they retain the inherited contract.

Keep comments synchronized with behavior. Tests remain the executable specification for protocol edge cases.
