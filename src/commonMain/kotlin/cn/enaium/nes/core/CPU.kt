package cn.enaium.nes.core

// ----------------------------------------------------------------------------
// Addressing modes
// ----------------------------------------------------------------------------
private const val ADDR_ZP = 0 //          Zero page         — operand at $00XX
private const val ADDR_REL = 1 //         Relative          — PC + signed 8-bit offset (branches)
private const val ADDR_IMP = 2 //         Implied           — no operand (e.g. CLC, RTS, TAX)
private const val ADDR_ABS = 3 //         Absolute          — operand at $XXXX (any address)
private const val ADDR_ACC = 4 //         Accumulator       — operand is the A register itself
private const val ADDR_IMM = 5 //         Immediate         — operand is a literal byte (LDA #$42)
private const val ADDR_ZPX = 6 //         Zero page,X       — operand at ($XX + X) & $FF
private const val ADDR_ZPY = 7 //         Zero page,Y       — operand at ($XX + Y) & $FF
private const val ADDR_ABSX = 8 //        Absolute,X        — operand at $XXXX + X
private const val ADDR_ABSY = 9 //        Absolute,Y        — operand at $XXXX + Y
private const val ADDR_PREIDXIND = 10 //  (Indirect,X)      — pointer at ($XX + X) in zero page
private const val ADDR_POSTIDXIND = 11 // (Indirect),Y      — pointer at $XX in zero page, then + Y
private const val ADDR_INDABS = 12 //     Indirect absolute — pointer at $XXXX (JMP indirect only)

// ----------------------------------------------------------------------------
// Instructions
// ----------------------------------------------------------------------------
// Arithmetic & logic
private const val INS_ADC = 0
private const val INS_AND = 1
private const val INS_ASL = 2
// Branches
private const val INS_BCC = 3
private const val INS_BCS = 4
private const val INS_BEQ = 5
private const val INS_BIT = 6
private const val INS_BMI = 7
private const val INS_BNE = 8
private const val INS_BPL = 9
private const val INS_BRK = 10
private const val INS_BVC = 11
private const val INS_BVS = 12
// Flag clears
private const val INS_CLC = 13
private const val INS_CLD = 14
private const val INS_CLI = 15
private const val INS_CLV = 16
// Compares
private const val INS_CMP = 17
private const val INS_CPX = 18
private const val INS_CPY = 19
// Decrements
private const val INS_DEC = 20
private const val INS_DEX = 21
private const val INS_DEY = 22
// XOR
private const val INS_EOR = 23
// Increments
private const val INS_INC = 24
private const val INS_INX = 25
private const val INS_INY = 26
// Jumps
private const val INS_JMP = 27
private const val INS_JSR = 28
// Loads
private const val INS_LDA = 29
private const val INS_LDX = 30
private const val INS_LDY = 31
// Shift
private const val INS_LSR = 32
// No-op
private const val INS_NOP = 33
// OR
private const val INS_ORA = 34
// Stack pushes/pulls
private const val INS_PHA = 35
private const val INS_PHP = 36
private const val INS_PLA = 37
private const val INS_PLP = 38
// Rotates
private const val INS_ROL = 39
private const val INS_ROR = 40
// Returns
private const val INS_RTI = 41
private const val INS_RTS = 42
// Subtract
private const val INS_SBC = 43
// Flag sets
private const val INS_SEC = 44
private const val INS_SED = 45
private const val INS_SEI = 46
// Stores
private const val INS_STA = 47
private const val INS_STX = 48
private const val INS_STY = 49
// Register transfers
private const val INS_TAX = 50
private const val INS_TAY = 51
private const val INS_TSX = 52
private const val INS_TXA = 53
private const val INS_TXS = 54
private const val INS_TYA = 55

// Unofficial opcodes
private const val INS_ALR = 56 // ALR (ASR) — AND then LSR
private const val INS_ANC = 57 // ANC — AND with bit 7 copied to carry
private const val INS_ARR = 58 // ARR — AND then ROR, peculiar N/V/C side effects
private const val INS_AXS = 59 // AXS (SBX) — X = (A & X) - #imm
private const val INS_LAX = 60 // LAX — Load A and X
private const val INS_SAX = 61 // SAX — Store (A & X)
private const val INS_DCP = 62 // DCP — DEC memory then CMP
private const val INS_ISC = 63 // ISC — INC memory then SBC
private const val INS_RLA = 64 // RLA — ROL memory then AND
private const val INS_RRA = 65 // RRA — ROR memory then ADC
private const val INS_SLO = 66 // SLO — ASL memory then ORA
private const val INS_SRE = 67 // SRE — LSR memory then EOR
private const val INS_SKB = 68 // SKB — 2-byte NOP
private const val INS_IGN = 69 // IGN — 3-byte NOP that still reads memory

// "Unstable" opcodes whose stored value depends on the internal bus
// arbitration between CPU cycles (and DMC DMA hijacking).
private const val INS_SHA = 71 // SHA (AHX) — Store A & X & (H+1)
private const val INS_SHS = 72 // SHS (TAS) — SP = A & X, store SP & (H+1)
private const val INS_SHY = 73 // SHY (SYA) — Store Y & (H+1)
private const val INS_SHX = 74 // SHX (SXA) — Store X & (H+1)
private const val INS_LAE = 75 // LAE (LAS) — A = X = SP = memory & SP

// Opcodes whose behavior depends on a "magic" constant (manufacturing-run
// dependent). Tests only exercise inputs where the magic value cancels out.
private const val INS_ANE = 76 // ANE (XAA) — A = (A | magic) & X & #imm
private const val INS_LXA = 77 // LXA (ATX) — A = X = (A | magic) & #imm

private data class OpcodeEntry(
    val ins: Int,
    val mode: Int,
    val size: Int,
    val cycles: Int,
)

private val INVALID_OPCODE = OpcodeEntry(ins = -1, mode = 0, size = 1, cycles = 2)

// OPCODE_TABLE indexed by opcode byte (0-255). Unassigned bytes are null and
// resolve to INVALID_OPCODE at dispatch. Size/cycle counts from the datasheet.
private val OPCODE_TABLE: Array<OpcodeEntry?> = arrayOfNulls<OpcodeEntry>(256).also { t ->
    // ADC — Add with carry
    t[0x69] = OpcodeEntry(INS_ADC, ADDR_IMM, 2, 2)
    t[0x65] = OpcodeEntry(INS_ADC, ADDR_ZP, 2, 3)
    t[0x75] = OpcodeEntry(INS_ADC, ADDR_ZPX, 2, 4)
    t[0x6d] = OpcodeEntry(INS_ADC, ADDR_ABS, 3, 4)
    t[0x7d] = OpcodeEntry(INS_ADC, ADDR_ABSX, 3, 4)
    t[0x79] = OpcodeEntry(INS_ADC, ADDR_ABSY, 3, 4)
    t[0x61] = OpcodeEntry(INS_ADC, ADDR_PREIDXIND, 2, 6)
    t[0x71] = OpcodeEntry(INS_ADC, ADDR_POSTIDXIND, 2, 5)

    // AND — Bitwise AND with accumulator
    t[0x29] = OpcodeEntry(INS_AND, ADDR_IMM, 2, 2)
    t[0x25] = OpcodeEntry(INS_AND, ADDR_ZP, 2, 3)
    t[0x35] = OpcodeEntry(INS_AND, ADDR_ZPX, 2, 4)
    t[0x2d] = OpcodeEntry(INS_AND, ADDR_ABS, 3, 4)
    t[0x3d] = OpcodeEntry(INS_AND, ADDR_ABSX, 3, 4)
    t[0x39] = OpcodeEntry(INS_AND, ADDR_ABSY, 3, 4)
    t[0x21] = OpcodeEntry(INS_AND, ADDR_PREIDXIND, 2, 6)
    t[0x31] = OpcodeEntry(INS_AND, ADDR_POSTIDXIND, 2, 5)

    // ASL — Arithmetic shift left
    t[0x0a] = OpcodeEntry(INS_ASL, ADDR_ACC, 1, 2)
    t[0x06] = OpcodeEntry(INS_ASL, ADDR_ZP, 2, 5)
    t[0x16] = OpcodeEntry(INS_ASL, ADDR_ZPX, 2, 6)
    t[0x0e] = OpcodeEntry(INS_ASL, ADDR_ABS, 3, 6)
    t[0x1e] = OpcodeEntry(INS_ASL, ADDR_ABSX, 3, 7)

    // Branches
    t[0x90] = OpcodeEntry(INS_BCC, ADDR_REL, 2, 2)
    t[0xb0] = OpcodeEntry(INS_BCS, ADDR_REL, 2, 2)
    t[0xf0] = OpcodeEntry(INS_BEQ, ADDR_REL, 2, 2)
    t[0x30] = OpcodeEntry(INS_BMI, ADDR_REL, 2, 2)
    t[0xd0] = OpcodeEntry(INS_BNE, ADDR_REL, 2, 2)
    t[0x10] = OpcodeEntry(INS_BPL, ADDR_REL, 2, 2)
    t[0x50] = OpcodeEntry(INS_BVC, ADDR_REL, 2, 2)
    t[0x70] = OpcodeEntry(INS_BVS, ADDR_REL, 2, 2)

    // BIT
    t[0x24] = OpcodeEntry(INS_BIT, ADDR_ZP, 2, 3)
    t[0x2c] = OpcodeEntry(INS_BIT, ADDR_ABS, 3, 4)

    // BRK
    t[0x00] = OpcodeEntry(INS_BRK, ADDR_IMP, 1, 7)

    // Flag clears
    t[0x18] = OpcodeEntry(INS_CLC, ADDR_IMP, 1, 2)
    t[0xd8] = OpcodeEntry(INS_CLD, ADDR_IMP, 1, 2)
    t[0x58] = OpcodeEntry(INS_CLI, ADDR_IMP, 1, 2)
    t[0xb8] = OpcodeEntry(INS_CLV, ADDR_IMP, 1, 2)

    // CMP
    t[0xc9] = OpcodeEntry(INS_CMP, ADDR_IMM, 2, 2)
    t[0xc5] = OpcodeEntry(INS_CMP, ADDR_ZP, 2, 3)
    t[0xd5] = OpcodeEntry(INS_CMP, ADDR_ZPX, 2, 4)
    t[0xcd] = OpcodeEntry(INS_CMP, ADDR_ABS, 3, 4)
    t[0xdd] = OpcodeEntry(INS_CMP, ADDR_ABSX, 3, 4)
    t[0xd9] = OpcodeEntry(INS_CMP, ADDR_ABSY, 3, 4)
    t[0xc1] = OpcodeEntry(INS_CMP, ADDR_PREIDXIND, 2, 6)
    t[0xd1] = OpcodeEntry(INS_CMP, ADDR_POSTIDXIND, 2, 5)

    // CPX
    t[0xe0] = OpcodeEntry(INS_CPX, ADDR_IMM, 2, 2)
    t[0xe4] = OpcodeEntry(INS_CPX, ADDR_ZP, 2, 3)
    t[0xec] = OpcodeEntry(INS_CPX, ADDR_ABS, 3, 4)

    // CPY
    t[0xc0] = OpcodeEntry(INS_CPY, ADDR_IMM, 2, 2)
    t[0xc4] = OpcodeEntry(INS_CPY, ADDR_ZP, 2, 3)
    t[0xcc] = OpcodeEntry(INS_CPY, ADDR_ABS, 3, 4)

    // DEC
    t[0xc6] = OpcodeEntry(INS_DEC, ADDR_ZP, 2, 5)
    t[0xd6] = OpcodeEntry(INS_DEC, ADDR_ZPX, 2, 6)
    t[0xce] = OpcodeEntry(INS_DEC, ADDR_ABS, 3, 6)
    t[0xde] = OpcodeEntry(INS_DEC, ADDR_ABSX, 3, 7)

    // DEX / DEY
    t[0xca] = OpcodeEntry(INS_DEX, ADDR_IMP, 1, 2)
    t[0x88] = OpcodeEntry(INS_DEY, ADDR_IMP, 1, 2)

    // EOR
    t[0x49] = OpcodeEntry(INS_EOR, ADDR_IMM, 2, 2)
    t[0x45] = OpcodeEntry(INS_EOR, ADDR_ZP, 2, 3)
    t[0x55] = OpcodeEntry(INS_EOR, ADDR_ZPX, 2, 4)
    t[0x4d] = OpcodeEntry(INS_EOR, ADDR_ABS, 3, 4)
    t[0x5d] = OpcodeEntry(INS_EOR, ADDR_ABSX, 3, 4)
    t[0x59] = OpcodeEntry(INS_EOR, ADDR_ABSY, 3, 4)
    t[0x41] = OpcodeEntry(INS_EOR, ADDR_PREIDXIND, 2, 6)
    t[0x51] = OpcodeEntry(INS_EOR, ADDR_POSTIDXIND, 2, 5)

    // INC
    t[0xe6] = OpcodeEntry(INS_INC, ADDR_ZP, 2, 5)
    t[0xf6] = OpcodeEntry(INS_INC, ADDR_ZPX, 2, 6)
    t[0xee] = OpcodeEntry(INS_INC, ADDR_ABS, 3, 6)
    t[0xfe] = OpcodeEntry(INS_INC, ADDR_ABSX, 3, 7)

    // INX / INY
    t[0xe8] = OpcodeEntry(INS_INX, ADDR_IMP, 1, 2)
    t[0xc8] = OpcodeEntry(INS_INY, ADDR_IMP, 1, 2)

    // JMP
    t[0x4c] = OpcodeEntry(INS_JMP, ADDR_ABS, 3, 3)
    t[0x6c] = OpcodeEntry(INS_JMP, ADDR_INDABS, 3, 5)

    // JSR
    t[0x20] = OpcodeEntry(INS_JSR, ADDR_ABS, 3, 6)

    // LDA
    t[0xa9] = OpcodeEntry(INS_LDA, ADDR_IMM, 2, 2)
    t[0xa5] = OpcodeEntry(INS_LDA, ADDR_ZP, 2, 3)
    t[0xb5] = OpcodeEntry(INS_LDA, ADDR_ZPX, 2, 4)
    t[0xad] = OpcodeEntry(INS_LDA, ADDR_ABS, 3, 4)
    t[0xbd] = OpcodeEntry(INS_LDA, ADDR_ABSX, 3, 4)
    t[0xb9] = OpcodeEntry(INS_LDA, ADDR_ABSY, 3, 4)
    t[0xa1] = OpcodeEntry(INS_LDA, ADDR_PREIDXIND, 2, 6)
    t[0xb1] = OpcodeEntry(INS_LDA, ADDR_POSTIDXIND, 2, 5)

    // LDX
    t[0xa2] = OpcodeEntry(INS_LDX, ADDR_IMM, 2, 2)
    t[0xa6] = OpcodeEntry(INS_LDX, ADDR_ZP, 2, 3)
    t[0xb6] = OpcodeEntry(INS_LDX, ADDR_ZPY, 2, 4)
    t[0xae] = OpcodeEntry(INS_LDX, ADDR_ABS, 3, 4)
    t[0xbe] = OpcodeEntry(INS_LDX, ADDR_ABSY, 3, 4)

    // LDY
    t[0xa0] = OpcodeEntry(INS_LDY, ADDR_IMM, 2, 2)
    t[0xa4] = OpcodeEntry(INS_LDY, ADDR_ZP, 2, 3)
    t[0xb4] = OpcodeEntry(INS_LDY, ADDR_ZPX, 2, 4)
    t[0xac] = OpcodeEntry(INS_LDY, ADDR_ABS, 3, 4)
    t[0xbc] = OpcodeEntry(INS_LDY, ADDR_ABSX, 3, 4)

    // LSR
    t[0x4a] = OpcodeEntry(INS_LSR, ADDR_ACC, 1, 2)
    t[0x46] = OpcodeEntry(INS_LSR, ADDR_ZP, 2, 5)
    t[0x56] = OpcodeEntry(INS_LSR, ADDR_ZPX, 2, 6)
    t[0x4e] = OpcodeEntry(INS_LSR, ADDR_ABS, 3, 6)
    t[0x5e] = OpcodeEntry(INS_LSR, ADDR_ABSX, 3, 7)

    // NOP (official $EA plus unofficial single-byte NOPs)
    t[0x1a] = OpcodeEntry(INS_NOP, ADDR_IMP, 1, 2)
    t[0x3a] = OpcodeEntry(INS_NOP, ADDR_IMP, 1, 2)
    t[0x5a] = OpcodeEntry(INS_NOP, ADDR_IMP, 1, 2)
    t[0x7a] = OpcodeEntry(INS_NOP, ADDR_IMP, 1, 2)
    t[0xda] = OpcodeEntry(INS_NOP, ADDR_IMP, 1, 2)
    t[0xea] = OpcodeEntry(INS_NOP, ADDR_IMP, 1, 2)
    t[0xfa] = OpcodeEntry(INS_NOP, ADDR_IMP, 1, 2)

    // ORA
    t[0x09] = OpcodeEntry(INS_ORA, ADDR_IMM, 2, 2)
    t[0x05] = OpcodeEntry(INS_ORA, ADDR_ZP, 2, 3)
    t[0x15] = OpcodeEntry(INS_ORA, ADDR_ZPX, 2, 4)
    t[0x0d] = OpcodeEntry(INS_ORA, ADDR_ABS, 3, 4)
    t[0x1d] = OpcodeEntry(INS_ORA, ADDR_ABSX, 3, 4)
    t[0x19] = OpcodeEntry(INS_ORA, ADDR_ABSY, 3, 4)
    t[0x01] = OpcodeEntry(INS_ORA, ADDR_PREIDXIND, 2, 6)
    t[0x11] = OpcodeEntry(INS_ORA, ADDR_POSTIDXIND, 2, 5)

    // Stack pushes/pulls
    t[0x48] = OpcodeEntry(INS_PHA, ADDR_IMP, 1, 3)
    t[0x08] = OpcodeEntry(INS_PHP, ADDR_IMP, 1, 3)
    t[0x68] = OpcodeEntry(INS_PLA, ADDR_IMP, 1, 4)
    t[0x28] = OpcodeEntry(INS_PLP, ADDR_IMP, 1, 4)

    // ROL
    t[0x2a] = OpcodeEntry(INS_ROL, ADDR_ACC, 1, 2)
    t[0x26] = OpcodeEntry(INS_ROL, ADDR_ZP, 2, 5)
    t[0x36] = OpcodeEntry(INS_ROL, ADDR_ZPX, 2, 6)
    t[0x2e] = OpcodeEntry(INS_ROL, ADDR_ABS, 3, 6)
    t[0x3e] = OpcodeEntry(INS_ROL, ADDR_ABSX, 3, 7)

    // ROR
    t[0x6a] = OpcodeEntry(INS_ROR, ADDR_ACC, 1, 2)
    t[0x66] = OpcodeEntry(INS_ROR, ADDR_ZP, 2, 5)
    t[0x76] = OpcodeEntry(INS_ROR, ADDR_ZPX, 2, 6)
    t[0x6e] = OpcodeEntry(INS_ROR, ADDR_ABS, 3, 6)
    t[0x7e] = OpcodeEntry(INS_ROR, ADDR_ABSX, 3, 7)

    // RTI / RTS
    t[0x40] = OpcodeEntry(INS_RTI, ADDR_IMP, 1, 6)
    t[0x60] = OpcodeEntry(INS_RTS, ADDR_IMP, 1, 6)

    // SBC ($EB is an unofficial alternate of $E9)
    t[0xe9] = OpcodeEntry(INS_SBC, ADDR_IMM, 2, 2)
    t[0xeb] = OpcodeEntry(INS_SBC, ADDR_IMM, 2, 2)
    t[0xe5] = OpcodeEntry(INS_SBC, ADDR_ZP, 2, 3)
    t[0xf5] = OpcodeEntry(INS_SBC, ADDR_ZPX, 2, 4)
    t[0xed] = OpcodeEntry(INS_SBC, ADDR_ABS, 3, 4)
    t[0xfd] = OpcodeEntry(INS_SBC, ADDR_ABSX, 3, 4)
    t[0xf9] = OpcodeEntry(INS_SBC, ADDR_ABSY, 3, 4)
    t[0xe1] = OpcodeEntry(INS_SBC, ADDR_PREIDXIND, 2, 6)
    t[0xf1] = OpcodeEntry(INS_SBC, ADDR_POSTIDXIND, 2, 5)

    // Flag sets
    t[0x38] = OpcodeEntry(INS_SEC, ADDR_IMP, 1, 2)
    t[0xf8] = OpcodeEntry(INS_SED, ADDR_IMP, 1, 2)
    t[0x78] = OpcodeEntry(INS_SEI, ADDR_IMP, 1, 2)

    // STA
    t[0x85] = OpcodeEntry(INS_STA, ADDR_ZP, 2, 3)
    t[0x95] = OpcodeEntry(INS_STA, ADDR_ZPX, 2, 4)
    t[0x8d] = OpcodeEntry(INS_STA, ADDR_ABS, 3, 4)
    t[0x9d] = OpcodeEntry(INS_STA, ADDR_ABSX, 3, 5)
    t[0x99] = OpcodeEntry(INS_STA, ADDR_ABSY, 3, 5)
    t[0x81] = OpcodeEntry(INS_STA, ADDR_PREIDXIND, 2, 6)
    t[0x91] = OpcodeEntry(INS_STA, ADDR_POSTIDXIND, 2, 6)

    // STX
    t[0x86] = OpcodeEntry(INS_STX, ADDR_ZP, 2, 3)
    t[0x96] = OpcodeEntry(INS_STX, ADDR_ZPY, 2, 4)
    t[0x8e] = OpcodeEntry(INS_STX, ADDR_ABS, 3, 4)

    // STY
    t[0x84] = OpcodeEntry(INS_STY, ADDR_ZP, 2, 3)
    t[0x94] = OpcodeEntry(INS_STY, ADDR_ZPX, 2, 4)
    t[0x8c] = OpcodeEntry(INS_STY, ADDR_ABS, 3, 4)

    // Register transfers
    t[0xaa] = OpcodeEntry(INS_TAX, ADDR_IMP, 1, 2)
    t[0xa8] = OpcodeEntry(INS_TAY, ADDR_IMP, 1, 2)
    t[0xba] = OpcodeEntry(INS_TSX, ADDR_IMP, 1, 2)
    t[0x8a] = OpcodeEntry(INS_TXA, ADDR_IMP, 1, 2)
    t[0x9a] = OpcodeEntry(INS_TXS, ADDR_IMP, 1, 2)
    t[0x98] = OpcodeEntry(INS_TYA, ADDR_IMP, 1, 2)

    // --- Unofficial opcodes ---

    // ALR (ASR) — AND then LSR
    t[0x4b] = OpcodeEntry(INS_ALR, ADDR_IMM, 2, 2)

    // ANC — AND with carry = bit 7 of result
    t[0x0b] = OpcodeEntry(INS_ANC, ADDR_IMM, 2, 2)
    t[0x2b] = OpcodeEntry(INS_ANC, ADDR_IMM, 2, 2)

    // ARR — AND then ROR
    t[0x6b] = OpcodeEntry(INS_ARR, ADDR_IMM, 2, 2)

    // AXS (SBX) — X = (A & X) - immediate
    t[0xcb] = OpcodeEntry(INS_AXS, ADDR_IMM, 2, 2)

    // LAX — Load A and X
    t[0xa3] = OpcodeEntry(INS_LAX, ADDR_PREIDXIND, 2, 6)
    t[0xa7] = OpcodeEntry(INS_LAX, ADDR_ZP, 2, 3)
    t[0xaf] = OpcodeEntry(INS_LAX, ADDR_ABS, 3, 4)
    t[0xb3] = OpcodeEntry(INS_LAX, ADDR_POSTIDXIND, 2, 5)
    t[0xb7] = OpcodeEntry(INS_LAX, ADDR_ZPY, 2, 4)
    t[0xbf] = OpcodeEntry(INS_LAX, ADDR_ABSY, 3, 4)

    // SAX — Store (A & X)
    t[0x83] = OpcodeEntry(INS_SAX, ADDR_PREIDXIND, 2, 6)
    t[0x87] = OpcodeEntry(INS_SAX, ADDR_ZP, 2, 3)
    t[0x8f] = OpcodeEntry(INS_SAX, ADDR_ABS, 3, 4)
    t[0x97] = OpcodeEntry(INS_SAX, ADDR_ZPY, 2, 4)

    // DCP — DEC memory then CMP
    t[0xc3] = OpcodeEntry(INS_DCP, ADDR_PREIDXIND, 2, 8)
    t[0xc7] = OpcodeEntry(INS_DCP, ADDR_ZP, 2, 5)
    t[0xcf] = OpcodeEntry(INS_DCP, ADDR_ABS, 3, 6)
    t[0xd3] = OpcodeEntry(INS_DCP, ADDR_POSTIDXIND, 2, 8)
    t[0xd7] = OpcodeEntry(INS_DCP, ADDR_ZPX, 2, 6)
    t[0xdb] = OpcodeEntry(INS_DCP, ADDR_ABSY, 3, 7)
    t[0xdf] = OpcodeEntry(INS_DCP, ADDR_ABSX, 3, 7)

    // ISC (ISB) — INC memory then SBC
    t[0xe3] = OpcodeEntry(INS_ISC, ADDR_PREIDXIND, 2, 8)
    t[0xe7] = OpcodeEntry(INS_ISC, ADDR_ZP, 2, 5)
    t[0xef] = OpcodeEntry(INS_ISC, ADDR_ABS, 3, 6)
    t[0xf3] = OpcodeEntry(INS_ISC, ADDR_POSTIDXIND, 2, 8)
    t[0xf7] = OpcodeEntry(INS_ISC, ADDR_ZPX, 2, 6)
    t[0xfb] = OpcodeEntry(INS_ISC, ADDR_ABSY, 3, 7)
    t[0xff] = OpcodeEntry(INS_ISC, ADDR_ABSX, 3, 7)

    // RLA — ROL memory then AND
    t[0x23] = OpcodeEntry(INS_RLA, ADDR_PREIDXIND, 2, 8)
    t[0x27] = OpcodeEntry(INS_RLA, ADDR_ZP, 2, 5)
    t[0x2f] = OpcodeEntry(INS_RLA, ADDR_ABS, 3, 6)
    t[0x33] = OpcodeEntry(INS_RLA, ADDR_POSTIDXIND, 2, 8)
    t[0x37] = OpcodeEntry(INS_RLA, ADDR_ZPX, 2, 6)
    t[0x3b] = OpcodeEntry(INS_RLA, ADDR_ABSY, 3, 7)
    t[0x3f] = OpcodeEntry(INS_RLA, ADDR_ABSX, 3, 7)

    // RRA — ROR memory then ADC
    t[0x63] = OpcodeEntry(INS_RRA, ADDR_PREIDXIND, 2, 8)
    t[0x67] = OpcodeEntry(INS_RRA, ADDR_ZP, 2, 5)
    t[0x6f] = OpcodeEntry(INS_RRA, ADDR_ABS, 3, 6)
    t[0x73] = OpcodeEntry(INS_RRA, ADDR_POSTIDXIND, 2, 8)
    t[0x77] = OpcodeEntry(INS_RRA, ADDR_ZPX, 2, 6)
    t[0x7b] = OpcodeEntry(INS_RRA, ADDR_ABSY, 3, 7)
    t[0x7f] = OpcodeEntry(INS_RRA, ADDR_ABSX, 3, 7)

    // SLO — ASL memory then ORA
    t[0x03] = OpcodeEntry(INS_SLO, ADDR_PREIDXIND, 2, 8)
    t[0x07] = OpcodeEntry(INS_SLO, ADDR_ZP, 2, 5)
    t[0x0f] = OpcodeEntry(INS_SLO, ADDR_ABS, 3, 6)
    t[0x13] = OpcodeEntry(INS_SLO, ADDR_POSTIDXIND, 2, 8)
    t[0x17] = OpcodeEntry(INS_SLO, ADDR_ZPX, 2, 6)
    t[0x1b] = OpcodeEntry(INS_SLO, ADDR_ABSY, 3, 7)
    t[0x1f] = OpcodeEntry(INS_SLO, ADDR_ABSX, 3, 7)

    // SRE — LSR memory then EOR
    t[0x43] = OpcodeEntry(INS_SRE, ADDR_PREIDXIND, 2, 8)
    t[0x47] = OpcodeEntry(INS_SRE, ADDR_ZP, 2, 5)
    t[0x4f] = OpcodeEntry(INS_SRE, ADDR_ABS, 3, 6)
    t[0x53] = OpcodeEntry(INS_SRE, ADDR_POSTIDXIND, 2, 8)
    t[0x57] = OpcodeEntry(INS_SRE, ADDR_ZPX, 2, 6)
    t[0x5b] = OpcodeEntry(INS_SRE, ADDR_ABSY, 3, 7)
    t[0x5f] = OpcodeEntry(INS_SRE, ADDR_ABSX, 3, 7)

    // SKB — 2-byte NOP
    t[0x80] = OpcodeEntry(INS_SKB, ADDR_IMM, 2, 2)
    t[0x82] = OpcodeEntry(INS_SKB, ADDR_IMM, 2, 2)
    t[0x89] = OpcodeEntry(INS_SKB, ADDR_IMM, 2, 2)
    t[0xc2] = OpcodeEntry(INS_SKB, ADDR_IMM, 2, 2)
    t[0xe2] = OpcodeEntry(INS_SKB, ADDR_IMM, 2, 2)

    // IGN — 3-byte NOP that still performs a memory read
    t[0x0c] = OpcodeEntry(INS_IGN, ADDR_ABS, 3, 4)
    t[0x1c] = OpcodeEntry(INS_IGN, ADDR_ABSX, 3, 4)
    t[0x3c] = OpcodeEntry(INS_IGN, ADDR_ABSX, 3, 4)
    t[0x5c] = OpcodeEntry(INS_IGN, ADDR_ABSX, 3, 4)
    t[0x7c] = OpcodeEntry(INS_IGN, ADDR_ABSX, 3, 4)
    t[0xdc] = OpcodeEntry(INS_IGN, ADDR_ABSX, 3, 4)
    t[0xfc] = OpcodeEntry(INS_IGN, ADDR_ABSX, 3, 4)
    t[0x04] = OpcodeEntry(INS_IGN, ADDR_ZP, 2, 3)
    t[0x44] = OpcodeEntry(INS_IGN, ADDR_ZP, 2, 3)
    t[0x64] = OpcodeEntry(INS_IGN, ADDR_ZP, 2, 3)
    t[0x14] = OpcodeEntry(INS_IGN, ADDR_ZPX, 2, 4)
    t[0x34] = OpcodeEntry(INS_IGN, ADDR_ZPX, 2, 4)
    t[0x54] = OpcodeEntry(INS_IGN, ADDR_ZPX, 2, 4)
    t[0x74] = OpcodeEntry(INS_IGN, ADDR_ZPX, 2, 4)
    t[0xd4] = OpcodeEntry(INS_IGN, ADDR_ZPX, 2, 4)
    t[0xf4] = OpcodeEntry(INS_IGN, ADDR_ZPX, 2, 4)

    // SHA (AHX) — Store A & X & (H+1)
    t[0x93] = OpcodeEntry(INS_SHA, ADDR_POSTIDXIND, 2, 6)
    t[0x9f] = OpcodeEntry(INS_SHA, ADDR_ABSY, 3, 5)

    // SHS (TAS) — SP = A & X, then store SP & (H+1)
    t[0x9b] = OpcodeEntry(INS_SHS, ADDR_ABSY, 3, 5)

    // SHY (SYA) — Store Y & (H+1)
    t[0x9c] = OpcodeEntry(INS_SHY, ADDR_ABSX, 3, 5)

    // SHX (SXA) — Store X & (H+1)
    t[0x9e] = OpcodeEntry(INS_SHX, ADDR_ABSY, 3, 5)

    // LAE (LAS) — A = X = SP = memory & SP
    t[0xbb] = OpcodeEntry(INS_LAE, ADDR_ABSY, 3, 4)

    // ANE (XAA) — A = (A | magic) & X & immediate
    t[0x8b] = OpcodeEntry(INS_ANE, ADDR_IMM, 2, 2)

    // LXA — A = X = (A | magic) & immediate
    t[0xab] = OpcodeEntry(INS_LXA, ADDR_IMM, 2, 2)
}
class CPU(val nes: NES) {
    // IRQ Types
    val IRQ_NORMAL = 0
    val IRQ_NMI = 1
    val IRQ_RESET = 2

    // Main memory (zero-initialized, only non-zero regions set in init)
    var mem = IntArray(0x10000)

    // CPU Registers
    var REG_ACC = 0
    var REG_X = 0
    var REG_Y = 0
    var REG_SP = 0x01ff
    var REG_PC = 0x8000 - 1
    var REG_PC_NEW = 0x8000 - 1
    var REG_STATUS = 0x28

    // Status flags. F_ZERO stores the result byte, not a boolean: when the
    // result is 0, F_ZERO is 0 and the Z flag is considered set. All other
    // flags are 0 or 1.
    var F_CARRY = 0
    var F_DECIMAL = 0
    var F_INTERRUPT = 1
    var F_INTERRUPT_NEW = 1
    var F_OVERFLOW = 0
    var F_SIGN = 0
    var F_ZERO = 1

    var F_NOTUSED = 1
    var F_NOTUSED_NEW = 1
    var F_BRK = 1
    var F_BRK_NEW = 1

    var cyclesToHalt = 0
    var crash = false

    // Interrupt notification
    var irqRequested = false
    var irqType: Int? = null

    // NMI edge-detection pipeline. nmiRaised is set by _updateNmiOutput() on
    // rising edge; nmiPending means NMI fires at end of this emulate() call;
    // nmiImmediate means NMI fires at start of the next call (0-delay).
    var nmiRaised = false
    var nmiPending = false
    var nmiImmediate = false

    // Last value on the CPU data bus (open bus behavior)
    var dataBus = 0
    // Bus cycles completed in the current instruction
    var instrBusCycles = 0
    // APU frame counter cycles already advanced mid-instruction ($4015 catch-up)
    var apuCatchupCycles = 0
    // Running total of CPU cycles executed so far in the current frame
    var _cpuCycleBase = 0
    // Which bus cycle nmiRaised was set during (0-delay vs 1-delay NMI)
    var nmiRaisedAtCycle = 0
    // Remaining dots within the ppu.advanceDots() call that raised NMI
    var nmiDotsRemainingInStep = 0
    // Cycles until the next DMC DMA fetch (snapshotted at instruction start)
    var _dmcFetchCycles = 0

    private var useGameGenieLoader = false

    init {
        // Fill the internal RAM region with the 6502 power-on pattern
        mem.fill(0xff, 0, 0x2000)
        for (p in 0 until 4) {
            val j = p * 0x800
            mem[j + 0x008] = 0xf7
            mem[j + 0x009] = 0xef
            mem[j + 0x00a] = 0xdf
            mem[j + 0x00f] = 0xbf
        }

        REG_ACC = 0
        REG_X = 0
        REG_Y = 0
        REG_SP = 0x01ff
        REG_PC = 0x8000 - 1
        REG_PC_NEW = 0x8000 - 1
        REG_STATUS = 0x28

        setStatus(0x28)

        F_CARRY = 0
        F_DECIMAL = 0
        F_INTERRUPT = 1
        F_INTERRUPT_NEW = 1
        F_OVERFLOW = 0
        F_SIGN = 0
        F_ZERO = 1

        F_NOTUSED = 1
        F_NOTUSED_NEW = 1
        F_BRK = 1
        F_BRK_NEW = 1
    }

    // Emulates a single CPU instruction, returns the number of cycles.
    fun emulate(): Int {
        // 0-delay NMI: VBL edge was detected early enough in the previous
        // instruction (>= 5 PPU dots remaining), so the NMI sequence begins
        // instead of the next opcode fetch.
        if (nmiImmediate) {
            nmiImmediate = false
            nmiPending = false
            nmiRaised = false
            instrBusCycles = 0

            REG_PC_NEW = REG_PC
            F_INTERRUPT_NEW = F_INTERRUPT
            doNonMaskableInterrupt(getStatus() and 0xef)
            REG_PC = REG_PC_NEW
            F_INTERRUPT = F_INTERRUPT_NEW
            F_BRK = F_BRK_NEW
            _cpuCycleBase += 7
            return 7
        }

        var temp = 0
        var add = 0
        // High byte of the base address before index addition, used by
        // SHA/SHX/SHY/SHS to compute the stored value as REG & (H+1).
        var baseHigh = 0
        // NMI and IRQ each take 7 bus cycles that must be included in the
        // returned cycle count.
        var interruptCycles = 0

        // Promote nmiRaised to nmiPending (1-instruction delay between the
        // NMI assertion and it being serviced).
        if (nmiRaised) {
            nmiPending = true
            nmiRaised = false
        }

        // Check IRQ/reset at the start of each instruction.
        if (irqRequested) {
            temp = getStatus()

            REG_PC_NEW = REG_PC
            F_INTERRUPT_NEW = F_INTERRUPT
            when (irqType) {
                0 -> {
                    // Normal IRQ
                    if (F_INTERRUPT != 0) {
                        // Interrupt disabled, skip
                    } else {
                        // Clear the B flag (bit 4) for hardware interrupts
                        doIrq(temp and 0xef)
                        interruptCycles = 7
                    }
                }
                2 -> {
                    // Reset
                    doResetInterrupt()
                    interruptCycles = 7
                }
            }

            REG_PC = REG_PC_NEW
            F_INTERRUPT = F_INTERRUPT_NEW
            F_BRK = F_BRK_NEW
            irqRequested = false
        }

        if (nes.mmap == null) return 32

        // Reset bus cycle and APU catch-up counters for this instruction.
        instrBusCycles = 0
        apuCatchupCycles = 0
        nmiDotsRemainingInStep = 0

        // Snapshot cycles until next DMC DMA fetch (for SHx bus-hijack checks).
        _dmcFetchCycles = _cyclesToNextDmcFetch()

        // --- Fetch ---
        // Read the opcode byte at PC. (REG_PC is one less than the actual
        // instruction address, so the post-increment lands on the next one.)
        val opcode = loadFromCartridge(REG_PC + 1)
        dataBus = opcode
        instrBusCycles = 1
        nes.ppu.advanceDots(3)

        // --- Decode ---
        val opinfo = OPCODE_TABLE[opcode] ?: INVALID_OPCODE
        var cycleCount = opinfo.cycles
        var cycleAdd = 0 // extra cycles from page-crossing in indexed modes
        val addrMode = opinfo.mode

        val opaddr = REG_PC
        REG_PC += opinfo.size

        // --- Address (decode continued) ---
        var addr = 0
        when (addrMode) {
            ADDR_ZP -> {
                addr = loadDirect(opaddr + 2)
            }
            ADDR_REL -> {
                addr = loadDirect(opaddr + 2)
                if (addr < 0x80) {
                    addr += REG_PC
                } else {
                    addr += REG_PC - 256
                }
            }
            ADDR_IMP -> {
                // Dummy read of the next opcode byte at PC (real bus cycle)
                loadDirect(opaddr + 2)
            }
            ADDR_ABS -> {
                addr = load16bit(opaddr + 2)
            }
            ADDR_ACC -> {
                // Dummy read like implied mode, then use the accumulator
                loadDirect(opaddr + 2)
                addr = REG_ACC
            }
            ADDR_IMM -> {
                addr = REG_PC
            }
            ADDR_ZPX -> {
                val zpBase6 = loadDirect(opaddr + 2)
                loadDirect(zpBase6) // dummy read from unindexed zero-page address
                addr = (zpBase6 + REG_X) and 0xff
            }
            ADDR_ZPY -> {
                val zpBase7 = loadDirect(opaddr + 2)
                loadDirect(zpBase7) // dummy read from unindexed zero-page address
                addr = (zpBase7 + REG_Y) and 0xff
            }
            ADDR_ABSX -> {
                addr = load16bit(opaddr + 2)
                baseHigh = (addr shr 8) and 0xff
                if ((addr and 0xff00) != ((addr + REG_X) and 0xff00)) {
                    // Page boundary crossed: dummy read from the uncorrected address
                    load((addr and 0xff00) or ((addr + REG_X) and 0xff))
                    cycleAdd = 1
                }
                addr += REG_X
            }
            ADDR_ABSY -> {
                addr = load16bit(opaddr + 2)
                baseHigh = (addr shr 8) and 0xff
                if ((addr and 0xff00) != ((addr + REG_Y) and 0xff00)) {
                    load((addr and 0xff00) or ((addr + REG_Y) and 0xff))
                    cycleAdd = 1
                }
                addr += REG_Y
            }
            ADDR_PREIDXIND -> {
                val zpPtr10 = loadDirect(opaddr + 2)
                loadDirect(zpPtr10) // dummy read before adding X
                val zpAddr10 = (zpPtr10 + REG_X) and 0xff
                addr = loadDirect(zpAddr10) or (loadDirect((zpAddr10 + 1) and 0xff) shl 8)
            }
            ADDR_POSTIDXIND -> {
                val zpAddr = loadDirect(opaddr + 2)
                addr = loadDirect(zpAddr) or (loadDirect((zpAddr + 1) and 0xff) shl 8)
                baseHigh = (addr shr 8) and 0xff
                if ((addr and 0xff00) != ((addr + REG_Y) and 0xff00)) {
                    load((addr and 0xff00) or ((addr + REG_Y) and 0xff))
                    cycleAdd = 1
                }
                addr += REG_Y
            }
            ADDR_INDABS -> {
                // JMP indirect. The famous 6502 bug: when the pointer's low
                // byte is $FF, the high byte wraps within the same page.
                addr = load16bit(opaddr + 2)
                val hiAddr = (addr and 0xff00) or (((addr and 0xff) + 1) and 0xff)
                addr = load(addr) or (load(hiAddr) shl 8)
            }
        }
        // Wrap around for addresses above 0xFFFF
        addr = addr and 0xffff

        // ------------------------------------------------------------------
        // Execute
        // ------------------------------------------------------------------
        when (opinfo.ins) {
            INS_ADC -> {
                // Add with carry
                add = load(addr)
                temp = REG_ACC + add + F_CARRY

                if (
                    ((REG_ACC xor add) and 0x80) == 0 &&
                    ((REG_ACC xor temp) and 0x80) != 0
                ) {
                    F_OVERFLOW = 1
                } else {
                    F_OVERFLOW = 0
                }
                F_CARRY = if (temp > 255) 1 else 0
                F_SIGN = (temp shr 7) and 1
                F_ZERO = temp and 0xff
                REG_ACC = temp and 255
                cycleCount += cycleAdd
            }
            INS_AND -> {
                // AND memory with accumulator
                REG_ACC = REG_ACC and load(addr)
                F_SIGN = (REG_ACC shr 7) and 1
                F_ZERO = REG_ACC
                cycleCount += cycleAdd
            }
            INS_ASL -> {
                // Shift left one bit
                if (addrMode == ADDR_ACC) {
                    F_CARRY = (REG_ACC shr 7) and 1
                    REG_ACC = (REG_ACC shl 1) and 255
                    F_SIGN = (REG_ACC shr 7) and 1
                    F_ZERO = REG_ACC
                } else {
                    // Read-Modify-Write cycle pattern: dummy read (indexed, no
                    // page crossing), read, dummy write of original, write result.
                    if (
                        cycleAdd == 0 &&
                        (addrMode == ADDR_ABSX ||
                            addrMode == ADDR_ABSY ||
                            addrMode == ADDR_POSTIDXIND)
                    ) {
                        load(addr)
                    }
                    temp = load(addr)
                    write(addr, temp) // dummy write (original value)
                    F_CARRY = (temp shr 7) and 1
                    temp = (temp shl 1) and 255
                    F_SIGN = (temp shr 7) and 1
                    F_ZERO = temp
                    write(addr, temp)
                }
            }
            INS_BCC -> {
                // Branch on carry clear
                if (F_CARRY == 0) {
                    cycleCount += _takeBranch(opaddr, addr)
                }
            }
            INS_BCS -> {
                // Branch on carry set
                if (F_CARRY == 1) {
                    cycleCount += _takeBranch(opaddr, addr)
                }
            }
            INS_BEQ -> {
                // Branch on zero
                if (F_ZERO == 0) {
                    cycleCount += _takeBranch(opaddr, addr)
                }
            }
            INS_BIT -> {
                // Bit test: N <- M.7, V <- M.6, Z <- (A & M) == 0
                temp = load(addr)
                F_SIGN = (temp shr 7) and 1
                F_OVERFLOW = (temp shr 6) and 1
                temp = temp and REG_ACC
                F_ZERO = temp
            }
            INS_BMI -> {
                // Branch on negative result
                if (F_SIGN == 1) {
                    cycleCount += _takeBranch(opaddr, addr)
                }
            }
            INS_BNE -> {
                // Branch on not zero
                if (F_ZERO != 0) {
                    cycleCount += _takeBranch(opaddr, addr)
                }
            }
            INS_BPL -> {
                // Branch on positive result
                if (F_SIGN == 0) {
                    cycleCount += _takeBranch(opaddr, addr)
                }
            }
            INS_BRK -> {
                // Software interrupt: push PC+2 and status, jump via $FFFE
                REG_PC += 2
                push((REG_PC shr 8) and 255)
                push(REG_PC and 255)
                F_BRK = 1
                push(getStatus())

                F_INTERRUPT = 1
                REG_PC = load16bit(0xfffe)
                REG_PC--
            }
            INS_BVC -> {
                // Branch on overflow clear
                if (F_OVERFLOW == 0) {
                    cycleCount += _takeBranch(opaddr, addr)
                }
            }
            INS_BVS -> {
                // Branch on overflow set
                if (F_OVERFLOW == 1) {
                    cycleCount += _takeBranch(opaddr, addr)
                }
            }
            INS_CLC -> {
                // Clear carry flag
                F_CARRY = 0
            }
            INS_CLD -> {
                // Clear decimal flag (no effect on NES)
                F_DECIMAL = 0
            }
            INS_CLI -> {
                // Clear interrupt flag
                F_INTERRUPT = 0
            }
            INS_CLV -> {
                // Clear overflow flag
                F_OVERFLOW = 0
            }
            INS_CMP -> {
                // Compare memory and accumulator
                temp = REG_ACC - load(addr)
                F_CARRY = if (temp >= 0) 1 else 0
                F_SIGN = (temp shr 7) and 1
                F_ZERO = temp and 0xff
                cycleCount += cycleAdd
            }
            INS_CPX -> {
                // Compare memory and index X
                temp = REG_X - load(addr)
                F_CARRY = if (temp >= 0) 1 else 0
                F_SIGN = (temp shr 7) and 1
                F_ZERO = temp and 0xff
            }
            INS_CPY -> {
                // Compare memory and index Y
                temp = REG_Y - load(addr)
                F_CARRY = if (temp >= 0) 1 else 0
                F_SIGN = (temp shr 7) and 1
                F_ZERO = temp and 0xff
            }
            INS_DEC -> {
                // Decrement memory by one (RMW pattern, see INS_ASL)
                if (
                    cycleAdd == 0 &&
                    (addrMode == ADDR_ABSX ||
                        addrMode == ADDR_ABSY ||
                        addrMode == ADDR_POSTIDXIND)
                ) {
                    load(addr)
                }
                temp = load(addr)
                write(addr, temp) // dummy write (original value)
                temp = (temp - 1) and 0xff
                F_SIGN = (temp shr 7) and 1
                F_ZERO = temp
                write(addr, temp)
            }
            INS_DEX -> {
                // Decrement index X by one
                REG_X = (REG_X - 1) and 0xff
                F_SIGN = (REG_X shr 7) and 1
                F_ZERO = REG_X
            }
            INS_DEY -> {
                // Decrement index Y by one
                REG_Y = (REG_Y - 1) and 0xff
                F_SIGN = (REG_Y shr 7) and 1
                F_ZERO = REG_Y
            }
            INS_EOR -> {
                // XOR memory with accumulator
                REG_ACC = (load(addr) xor REG_ACC) and 0xff
                F_SIGN = (REG_ACC shr 7) and 1
                F_ZERO = REG_ACC
                cycleCount += cycleAdd
            }
            INS_INC -> {
                // Increment memory by one (RMW pattern, see INS_ASL)
                if (
                    cycleAdd == 0 &&
                    (addrMode == ADDR_ABSX ||
                        addrMode == ADDR_ABSY ||
                        addrMode == ADDR_POSTIDXIND)
                ) {
                    load(addr)
                }
                temp = load(addr)
                write(addr, temp) // dummy write (original value)
                temp = (temp + 1) and 0xff
                F_SIGN = (temp shr 7) and 1
                F_ZERO = temp
                write(addr, temp)
            }
            INS_INX -> {
                // Increment index X by one
                REG_X = (REG_X + 1) and 0xff
                F_SIGN = (REG_X shr 7) and 1
                F_ZERO = REG_X
            }
            INS_INY -> {
                // Increment index Y by one
                REG_Y = (REG_Y + 1) and 0xff
                F_SIGN = (REG_Y shr 7) and 1
                F_ZERO = REG_Y
            }
            INS_JMP -> {
                // Jump to new location
                REG_PC = addr - 1
            }
            INS_JSR -> {
                // Jump to new location, saving return address
                push((REG_PC shr 8) and 255)
                push(REG_PC and 255)
                // Last cycle reads the high byte of the target (open bus behavior)
                loadDirect(opaddr + 3)
                REG_PC = addr - 1
            }
            INS_LDA -> {
                // Load accumulator with memory
                REG_ACC = load(addr)
                F_SIGN = (REG_ACC shr 7) and 1
                F_ZERO = REG_ACC
                cycleCount += cycleAdd
            }
            INS_LDX -> {
                // Load index X with memory
                REG_X = load(addr)
                F_SIGN = (REG_X shr 7) and 1
                F_ZERO = REG_X
                cycleCount += cycleAdd
            }
            INS_LDY -> {
                // Load index Y with memory
                REG_Y = load(addr)
                F_SIGN = (REG_Y shr 7) and 1
                F_ZERO = REG_Y
                cycleCount += cycleAdd
            }
            INS_LSR -> {
                // Shift right one bit (RMW pattern, see INS_ASL)
                if (addrMode == ADDR_ACC) {
                    temp = REG_ACC and 0xff
                    F_CARRY = temp and 1
                    temp = temp shr 1
                    REG_ACC = temp
                } else {
                    if (
                        cycleAdd == 0 &&
                        (addrMode == ADDR_ABSX ||
                            addrMode == ADDR_ABSY ||
                            addrMode == ADDR_POSTIDXIND)
                    ) {
                        load(addr)
                    }
                    temp = load(addr) and 0xff
                    write(addr, temp) // dummy write (original value)
                    F_CARRY = temp and 1
                    temp = temp shr 1
                    write(addr, temp)
                }
                F_SIGN = 0
                F_ZERO = temp
            }
            INS_NOP -> {
                // No OPeration
            }
            INS_ORA -> {
                // OR memory with accumulator
                temp = (load(addr) or REG_ACC) and 255
                F_SIGN = (temp shr 7) and 1
                F_ZERO = temp
                REG_ACC = temp
                cycleCount += cycleAdd
            }
            INS_PHA -> {
                // Push accumulator on stack
                push(REG_ACC)
            }
            INS_PHP -> {
                // Push processor status on stack
                F_BRK = 1
                push(getStatus())
            }
            INS_PLA -> {
                // Pull accumulator from stack
                REG_ACC = pull()
                F_SIGN = (REG_ACC shr 7) and 1
                F_ZERO = REG_ACC
            }
            INS_PLP -> {
                // Pull processor status from stack
                setStatusFromStack(pull())
            }
            INS_ROL -> {
                // Rotate one bit left (RMW pattern, see INS_ASL)
                if (addrMode == ADDR_ACC) {
                    temp = REG_ACC
                    add = F_CARRY
                    F_CARRY = (temp shr 7) and 1
                    temp = ((temp shl 1) and 0xff) + add
                    REG_ACC = temp
                } else {
                    if (
                        cycleAdd == 0 &&
                        (addrMode == ADDR_ABSX ||
                            addrMode == ADDR_ABSY ||
                            addrMode == ADDR_POSTIDXIND)
                    ) {
                        load(addr)
                    }
                    temp = load(addr)
                    write(addr, temp) // dummy write (original value)
                    add = F_CARRY
                    F_CARRY = (temp shr 7) and 1
                    temp = ((temp shl 1) and 0xff) + add
                    write(addr, temp)
                }
                F_SIGN = (temp shr 7) and 1
                F_ZERO = temp
            }
            INS_ROR -> {
                // Rotate one bit right (RMW pattern, see INS_ASL)
                if (addrMode == ADDR_ACC) {
                    add = F_CARRY shl 7
                    F_CARRY = REG_ACC and 1
                    temp = (REG_ACC shr 1) + add
                    REG_ACC = temp
                } else {
                    if (
                        cycleAdd == 0 &&
                        (addrMode == ADDR_ABSX ||
                            addrMode == ADDR_ABSY ||
                            addrMode == ADDR_POSTIDXIND)
                    ) {
                        load(addr)
                    }
                    temp = load(addr)
                    write(addr, temp) // dummy write (original value)
                    add = F_CARRY shl 7
                    F_CARRY = temp and 1
                    temp = (temp shr 1) + add
                    write(addr, temp)
                }
                F_SIGN = (temp shr 7) and 1
                F_ZERO = temp
            }
            INS_RTI -> {
                // Return from interrupt: pull status and PC
                setStatusFromStack(pull())

                REG_PC = pull()
                REG_PC += pull() shl 8
                if (REG_PC == 0xffff) {
                    return 0
                }
                REG_PC--
            }
            INS_RTS -> {
                // Return from subroutine: pull PC
                REG_PC = pull()
                REG_PC += pull() shl 8

                if (REG_PC == 0xffff) {
                    return 0 // return from NSF play routine
                }
            }
            INS_SBC -> {
                // Subtract memory from accumulator with borrow
                add = load(addr)
                temp = REG_ACC - add - (1 - F_CARRY)
                F_SIGN = (temp shr 7) and 1
                F_ZERO = temp and 0xff
                if (
                    ((REG_ACC xor temp) and 0x80) != 0 &&
                    ((REG_ACC xor add) and 0x80) != 0
                ) {
                    F_OVERFLOW = 1
                } else {
                    F_OVERFLOW = 0
                }
                F_CARRY = if (temp < 0) 0 else 1
                REG_ACC = temp and 0xff
                cycleCount += cycleAdd
            }
            INS_SEC -> {
                // Set carry flag
                F_CARRY = 1
            }
            INS_SED -> {
                // Set decimal mode (no effect on NES)
                F_DECIMAL = 1
            }
            INS_SEI -> {
                // Set interrupt disable status
                F_INTERRUPT = 1
            }
            INS_STA -> {
                // Store accumulator in memory. Stores always take the extra
                // cycle for indexed addressing, even without a page crossing.
                if (
                    cycleAdd == 0 &&
                    (addrMode == ADDR_ABSX ||
                        addrMode == ADDR_ABSY ||
                        addrMode == ADDR_POSTIDXIND)
                ) {
                    load(addr)
                }
                write(addr, REG_ACC)
            }
            INS_STX -> {
                // Store index X in memory
                write(addr, REG_X)
            }
            INS_STY -> {
                // Store index Y in memory
                write(addr, REG_Y)
            }
            INS_TAX -> {
                // Transfer accumulator to index X
                REG_X = REG_ACC
                F_SIGN = (REG_ACC shr 7) and 1
                F_ZERO = REG_ACC
            }
            INS_TAY -> {
                // Transfer accumulator to index Y
                REG_Y = REG_ACC
                F_SIGN = (REG_ACC shr 7) and 1
                F_ZERO = REG_ACC
            }
            INS_TSX -> {
                // Transfer stack pointer to index X
                REG_X = REG_SP and 0xff
                F_SIGN = (REG_SP shr 7) and 1
                F_ZERO = REG_X
            }
            INS_TXA -> {
                // Transfer index X to accumulator
                REG_ACC = REG_X
                F_SIGN = (REG_X shr 7) and 1
                F_ZERO = REG_X
            }
            INS_TXS -> {
                // Transfer index X to stack pointer
                REG_SP = REG_X and 0xff
            }
            INS_TYA -> {
                // Transfer index Y to accumulator
                REG_ACC = REG_Y
                F_SIGN = (REG_Y shr 7) and 1
                F_ZERO = REG_Y
            }
            INS_ALR -> {
                // Shift right one bit after ANDing
                temp = REG_ACC and load(addr)
                F_CARRY = temp and 1
                REG_ACC = temp shr 1
                F_ZERO = REG_ACC
                F_SIGN = 0
            }
            INS_ANC -> {
                // AND accumulator, carry = bit 7 of result
                REG_ACC = REG_ACC and load(addr)
                F_ZERO = REG_ACC
                F_CARRY = (REG_ACC shr 7) and 1
                F_SIGN = F_CARRY
            }
            INS_ARR -> {
                // Rotate right one bit after ANDing
                temp = REG_ACC and load(addr)
                REG_ACC = (temp shr 1) + (F_CARRY shl 7)
                F_ZERO = REG_ACC
                F_SIGN = F_CARRY
                F_CARRY = (temp shr 7) and 1
                F_OVERFLOW = ((temp shr 7) xor (temp shr 6)) and 1
            }
            INS_AXS -> {
                // Set X to (X AND A) - value. Sets N, Z, C; does not affect V.
                temp = (REG_X and REG_ACC) - load(addr)
                F_SIGN = (temp shr 7) and 1
                F_ZERO = temp and 0xff
                F_CARRY = if (temp < 0) 0 else 1
                REG_X = temp and 0xff
            }
            INS_LAX -> {
                // Load A and X with memory
                REG_ACC = load(addr)
                REG_X = REG_ACC
                F_ZERO = REG_ACC
                F_SIGN = (REG_ACC shr 7) and 1
                cycleCount += cycleAdd
            }
            INS_SAX -> {
                // Store A AND X in memory
                write(addr, REG_ACC and REG_X)
            }
            INS_DCP -> {
                // Decrement memory then compare (RMW pattern, see INS_ASL)
                if (
                    cycleAdd == 0 &&
                    (addrMode == ADDR_ABSX ||
                        addrMode == ADDR_ABSY ||
                        addrMode == ADDR_POSTIDXIND)
                ) {
                    load(addr)
                }
                temp = load(addr)
                write(addr, temp) // dummy write (original value)
                temp = (temp - 1) and 0xff
                write(addr, temp)

                // Then compare with the accumulator
                temp = REG_ACC - temp
                F_CARRY = if (temp >= 0) 1 else 0
                F_SIGN = (temp shr 7) and 1
                F_ZERO = temp and 0xff
            }
            INS_ISC -> {
                // Increment memory then subtract (RMW pattern, see INS_ASL)
                if (
                    cycleAdd == 0 &&
                    (addrMode == ADDR_ABSX ||
                        addrMode == ADDR_ABSY ||
                        addrMode == ADDR_POSTIDXIND)
                ) {
                    load(addr)
                }
                temp = load(addr)
                write(addr, temp) // dummy write (original value)
                temp = (temp + 1) and 0xff
                write(addr, temp)

                // Then subtract from the accumulator
                val isbVal = temp
                temp = REG_ACC - isbVal - (1 - F_CARRY)
                F_SIGN = (temp shr 7) and 1
                F_ZERO = temp and 0xff
                if (
                    ((REG_ACC xor temp) and 0x80) != 0 &&
                    ((REG_ACC xor isbVal) and 0x80) != 0
                ) {
                    F_OVERFLOW = 1
                } else {
                    F_OVERFLOW = 0
                }
                F_CARRY = if (temp < 0) 0 else 1
                REG_ACC = temp and 0xff
            }
            INS_RLA -> {
                // Rotate left then AND (RMW pattern, see INS_ASL)
                if (
                    cycleAdd == 0 &&
                    (addrMode == ADDR_ABSX ||
                        addrMode == ADDR_ABSY ||
                        addrMode == ADDR_POSTIDXIND)
                ) {
                    load(addr)
                }
                temp = load(addr)
                write(addr, temp) // dummy write (original value)
                add = F_CARRY
                F_CARRY = (temp shr 7) and 1
                temp = ((temp shl 1) and 0xff) + add
                write(addr, temp)

                // Then AND with the accumulator
                REG_ACC = REG_ACC and temp
                F_SIGN = (REG_ACC shr 7) and 1
                F_ZERO = REG_ACC
            }
            INS_RRA -> {
                // Rotate right then add (RMW pattern, see INS_ASL)
                if (
                    cycleAdd == 0 &&
                    (addrMode == ADDR_ABSX ||
                        addrMode == ADDR_ABSY ||
                        addrMode == ADDR_POSTIDXIND)
                ) {
                    load(addr)
                }
                temp = load(addr)
                write(addr, temp) // dummy write (original value)
                add = F_CARRY shl 7
                F_CARRY = temp and 1
                temp = (temp shr 1) + add
                write(addr, temp)

                // Then add to the accumulator
                val rraVal = temp
                temp = REG_ACC + rraVal + F_CARRY

                if (
                    ((REG_ACC xor rraVal) and 0x80) == 0 &&
                    ((REG_ACC xor temp) and 0x80) != 0
                ) {
                    F_OVERFLOW = 1
                } else {
                    F_OVERFLOW = 0
                }
                F_CARRY = if (temp > 255) 1 else 0
                F_SIGN = (temp shr 7) and 1
                F_ZERO = temp and 0xff
                REG_ACC = temp and 255
            }
            INS_SLO -> {
                // Shift left then OR (RMW pattern, see INS_ASL)
                if (
                    cycleAdd == 0 &&
                    (addrMode == ADDR_ABSX ||
                        addrMode == ADDR_ABSY ||
                        addrMode == ADDR_POSTIDXIND)
                ) {
                    load(addr)
                }
                temp = load(addr)
                write(addr, temp) // dummy write (original value)
                F_CARRY = (temp shr 7) and 1
                temp = (temp shl 1) and 255
                write(addr, temp)

                // Then OR with the accumulator
                REG_ACC = REG_ACC or temp
                F_SIGN = (REG_ACC shr 7) and 1
                F_ZERO = REG_ACC
            }
            INS_SRE -> {
                // Shift right then XOR (RMW pattern, see INS_ASL)
                if (
                    cycleAdd == 0 &&
                    (addrMode == ADDR_ABSX ||
                        addrMode == ADDR_ABSY ||
                        addrMode == ADDR_POSTIDXIND)
                ) {
                    load(addr)
                }
                temp = load(addr) and 0xff
                write(addr, temp) // dummy write (original value)
                F_CARRY = temp and 1
                temp = temp shr 1
                write(addr, temp)

                // Then XOR with the accumulator
                REG_ACC = REG_ACC xor temp
                F_SIGN = (REG_ACC shr 7) and 1
                F_ZERO = REG_ACC
            }
            INS_SKB -> {
                // 2-byte NOP: do nothing
            }
            INS_IGN -> {
                // 3-byte NOP that still reads memory
                load(addr)
                cycleCount += cycleAdd
            }
            INS_SHA -> {
                // Store A AND X AND (high byte of base address + 1)
                if (cycleAdd == 0) {
                    load(addr)
                }
                // If a DMC DMA fires during this instruction's read cycles,
                // the DMA hijacks the internal bus and the "& (H+1)" factor
                // is dropped.
                val dmaDuringInstr =
                    _dmcFetchCycles > 0 &&
                        _dmcFetchCycles <= instrBusCycles
                val shaVal = if (dmaDuringInstr) {
                    REG_ACC and REG_X
                } else {
                    REG_ACC and REG_X and ((baseHigh + 1) and 0xff)
                }
                if (cycleAdd == 1) {
                    addr = (shaVal shl 8) or (addr and 0xff)
                }
                write(addr, shaVal)
            }
            INS_SHS -> {
                // Transfer A AND X to SP, then store SP AND (high byte + 1)
                if (cycleAdd == 0) {
                    load(addr)
                }
                val dmaDuringInstr2 =
                    _dmcFetchCycles > 0 &&
                        _dmcFetchCycles <= instrBusCycles
                REG_SP = 0x0100 or (REG_ACC and REG_X)
                val shsVal = if (dmaDuringInstr2) {
                    REG_SP and 0xff
                } else {
                    REG_SP and 0xff and ((baseHigh + 1) and 0xff)
                }
                if (cycleAdd == 1) {
                    addr = (shsVal shl 8) or (addr and 0xff)
                }
                write(addr, shsVal)
            }
            INS_SHY -> {
                // Store Y AND (high byte of base address + 1)
                if (cycleAdd == 0) {
                    load(addr)
                }
                val dmaDuringInstr3 =
                    _dmcFetchCycles > 0 &&
                        _dmcFetchCycles <= instrBusCycles
                val shyVal = if (dmaDuringInstr3) {
                    REG_Y
                } else {
                    REG_Y and ((baseHigh + 1) and 0xff)
                }
                if (cycleAdd == 1) {
                    addr = (shyVal shl 8) or (addr and 0xff)
                }
                write(addr, shyVal)
            }
            INS_SHX -> {
                // Store X AND (high byte of base address + 1)
                if (cycleAdd == 0) {
                    load(addr)
                }
                val dmaDuringInstr4 =
                    _dmcFetchCycles > 0 &&
                        _dmcFetchCycles <= instrBusCycles
                val shxVal = if (dmaDuringInstr4) {
                    REG_X
                } else {
                    REG_X and ((baseHigh + 1) and 0xff)
                }
                if (cycleAdd == 1) {
                    addr = (shxVal shl 8) or (addr and 0xff)
                }
                write(addr, shxVal)
            }
            INS_LAE -> {
                // Load A, X, and SP with (memory AND SP)
                temp = load(addr) and (REG_SP and 0xff)
                REG_ACC = temp
                REG_X = temp
                F_ZERO = temp
                REG_SP = 0x0100 or temp
                F_SIGN = (temp shr 7) and 1
                cycleCount += cycleAdd
            }
            INS_ANE -> {
                // A = (A | MAGIC) & X & Immediate. Using MAGIC = $FF (the most
                // common value and the only one passing AccuracyCoin's tests).
                REG_ACC = (REG_ACC or 0xff) and REG_X and load(addr)
                F_ZERO = REG_ACC
                F_SIGN = (REG_ACC shr 7) and 1
            }
            INS_LXA -> {
                // A = (A | MAGIC) & Immediate, X = A. Same magic constant as ANE.
                REG_ACC = (REG_ACC or 0xff) and load(addr)
                REG_X = REG_ACC
                F_ZERO = REG_ACC
                F_SIGN = (REG_ACC shr 7) and 1
            }
            else -> {
                throw Error(
                    "Game crashed, invalid opcode at address " + opaddr.toString(16),
                )
            }
        } // end of instruction switch

        // Step PPU for any internal cycles not covered by bus operations
        // (RTS, RTI, PLA, PLP, JMP indirect have CPU-internal cycles).
        if (instrBusCycles < cycleCount) {
            val missingDots = (cycleCount - instrBusCycles) * 3
            // Update instrBusCycles BEFORE stepping the PPU so that if VBlank
            // fires during this step, nmiRaisedAtCycle is correct.
            instrBusCycles = cycleCount
            nes.ppu.advanceDots(missingDots)
        }

        // NMI delay: when nmiRaised was set during this instruction, determine
        // 0-delay vs 1-delay based on remaining PPU dots.
        if (nmiRaised) {
            val remainingDots =
                (instrBusCycles - nmiRaisedAtCycle) * 3 +
                    nmiDotsRemainingInStep
            if (remainingDots >= 5) {
                // 0-delay: NMI fires before the next instruction.
                nmiImmediate = true
                nmiRaised = false
            }
            // else: 1-delay, nmiRaised stays set for promotion at the start
            // of the next emulate() call.
        }

        // Fire NMI after the instruction completes.
        if (nmiPending) {
            REG_PC_NEW = REG_PC
            F_INTERRUPT_NEW = F_INTERRUPT
            // Clear the B flag (bit 4) for hardware interrupts
            doNonMaskableInterrupt(getStatus() and 0xef)
            REG_PC = REG_PC_NEW
            F_INTERRUPT = F_INTERRUPT_NEW
            F_BRK = F_BRK_NEW
            nmiPending = false
            interruptCycles = 7
        }

        _cpuCycleBase += cycleCount + interruptCycles
        return cycleCount + interruptCycles
    }

    // Reads from cartridge ROM, applying any active Game Genie patches. Used
    // for opcode fetches, operand reads, indirect jumps, and interrupt vectors.
    // In the JS this method is swapped at runtime; here a flag selects the
    // implementation, refreshed by _updateCartridgeLoader().
    fun loadFromCartridge(addr: Int): Int =
        if (useGameGenieLoader) {
            nes.gameGenie.applyCodes(addr, nes.mmap!!.load(addr))
        } else {
            nes.mmap!!.load(addr)
        }

    fun _updateCartridgeLoader() {
        useGameGenieLoader = nes.gameGenie.enabled && nes.gameGenie.patches.isNotEmpty()
    }

    // Each load() call represents one CPU bus read cycle. After the read,
    // advances the PPU by 3 dots to keep it in sync.
    fun load(addr: Int): Int {
        if (addr < 0x2000) {
            // RAM (zero page, stack, general): most common path
            dataBus = mem[addr and 0x7ff]
            instrBusCycles++
            nes.ppu.advanceDots(3)
        } else if (addr >= 0x4000) {
            // Cartridge ROM/RAM, APU, expansion ($4000+)
            if (addr == 0x4015) {
                // APU catch-up: advance frame counter before $4015 read so it
                // sees up-to-date length counter status and IRQ flags.
                nes.papu.advanceFrameCounter(instrBusCycles - apuCatchupCycles)
                apuCatchupCycles = instrBusCycles
                // $4015 reads are internal to the 2A03; the status value does
                // not drive the external data bus (open bus behavior).
                val apuStatus = loadFromCartridge(addr)
                instrBusCycles++
                nes.ppu.advanceDots(3)
                return apuStatus
            }
            dataBus = loadFromCartridge(addr)
            instrBusCycles++
            nes.ppu.advanceDots(3)
        } else {
            // PPU registers ($2000-$3FFF): increment bus cycle counter first
            // (for correct nmiRaisedAtCycle tracking), then read, then step PPU.
            instrBusCycles++
            dataBus = loadFromCartridge(addr)
            nes.ppu.advanceDots(3)
        }
        return dataBus
    }

    // Fast load for addresses guaranteed to be outside the PPU register range
    // ($2000-$3FFF) and APU status register ($4015). Still updates dataBus
    // (open bus behavior) and advances PPU/APU inline.
    fun loadDirect(addr: Int): Int {
        if (addr < 0x2000) {
            dataBus = mem[addr and 0x7ff]
        } else {
            dataBus = loadFromCartridge(addr)
        }
        instrBusCycles++
        nes.ppu.advanceDots(3)
        return dataBus
    }

    // Reads a 16-bit value as two separate bus operations with PPU stepping
    // between them, matching the real 6502's two-cycle read.
    fun load16bit(addr: Int): Int {
        var lo: Int
        if (addr < 0x1fff) {
            dataBus = mem[addr and 0x7ff]
            lo = dataBus
            instrBusCycles++
            nes.ppu.advanceDots(3)
            dataBus = mem[(addr + 1) and 0x7ff]
            instrBusCycles++
            nes.ppu.advanceDots(3)
            return lo or (dataBus shl 8)
        } else {
            dataBus = loadFromCartridge(addr)
            lo = dataBus
            instrBusCycles++
            nes.ppu.advanceDots(3)
            dataBus = loadFromCartridge(addr + 1)
            instrBusCycles++
            nes.ppu.advanceDots(3)
            return lo or (dataBus shl 8)
        }
    }

    // Each write() call represents one CPU bus write cycle. Write first, then
    // advance PPU by 3 dots.
    fun write(addr: Int, value: Int) {
        if (addr >= 0x2000 && addr < 0x4000) {
            // PPU register write: increment bus cycle counter first (so
            // nmiRaisedAtCycle is correct if _updateNmiOutput fires during
            // the write), then write, then step PPU.
            instrBusCycles++
            dataBus = value
            nes.mmap!!.write(addr, value)
            nes.ppu.advanceDots(3)
        } else {
            dataBus = value
            if (addr < 0x2000) {
                mem[addr and 0x7ff] = value
            } else {
                nes.mmap!!.write(addr, value)
            }
            instrBusCycles++
            nes.ppu.advanceDots(3)
        }
    }

    fun requestIrq(type: Int) {
        if (irqRequested) {
            if (type == IRQ_NORMAL) {
                return
            }
        }
        irqRequested = true
        irqType = type
    }

    fun push(value: Int) {
        dataBus = value
        // Stack is always $0100-$01FF (internal RAM), write directly to mem[]
        mem[REG_SP or 0x100] = value
        REG_SP--
        REG_SP = REG_SP and 0xff
        instrBusCycles++
        nes.ppu.advanceDots(3)
    }

    fun pull(): Int {
        REG_SP++
        REG_SP = REG_SP and 0xff
        // Stack is always $0100-$01FF (internal RAM), read directly from mem[]
        dataBus = mem[0x100 or REG_SP]
        instrBusCycles++
        nes.ppu.advanceDots(3)
        return dataBus
    }

    // --- DMC DMA bus hijacking ---
    // DMC DMA reads happen mid-instruction: the DMA unit steals a bus cycle to
    // fetch the next sample byte. SHx instructions (SHA/SHX/SHY/SHS) compute
    // their stored value partly from the address bus, so when a DMA read
    // hijacks the bus between address setup and the store, the "& (H+1)" factor
    // is lost. We approximate this by snapshotting how many cycles until the
    // next DMA fetch at instruction start (_dmcFetchCycles) and dropping the
    // factor when it would fire during the instruction's bus cycles.
    // Returns a large number if no DMA fetch is pending.
    fun _cyclesToNextDmcFetch(): Int {
        val dmc = nes.papu.dmc
        if (dmc == null || !dmc.isEnabled || dmc.dmaFrequency <= 0) {
            return 0x7fffffff
        }
        if (!dmc.hasSample) {
            return 0x7fffffff
        }
        // shiftCounter counts down in units of (nCycles << 3); each tick of
        // clockDmc consumes dmaFrequency units. The next DMA fetch occurs when
        // all remaining dmaCounter ticks of the shift register have elapsed.
        val cyclesPerClock = dmc.dmaFrequency shr 3
        var cyclesToFirstClock = (dmc.shiftCounter + 7) shr 3
        if (cyclesToFirstClock <= 0) cyclesToFirstClock = cyclesPerClock
        return cyclesToFirstClock + (dmc.dmaCounter - 1) * cyclesPerClock
    }

    // Branch dummy reads: when a branch is taken, the 6502 performs a dummy
    // read from the next sequential instruction address (cycle 3). On a page
    // crossing it performs an additional dummy read from the uncorrected
    // address (cycle 4). These are real bus operations.
    fun _takeBranch(opaddr: Int, addr: Int): Int {
        // Real addresses (REG_PC is offset by -1 from the real PC)
        val nextPC = (opaddr + 3) and 0xffff // address of next instruction
        val target = (addr + 1) and 0xffff // actual branch target

        // Cycle 3: dummy read from next instruction address
        load(nextPC)

        if ((nextPC and 0xff00) != (target and 0xff00)) {
            // Page crossing: cycle 4 dummy read from wrong address (unfixed PCH)
            val wrongAddr = (nextPC and 0xff00) or (target and 0x00ff)
            load(wrongAddr)
            REG_PC = addr
            return 2
        }
        REG_PC = addr
        return 1
    }

    fun pageCrossed(addr1: Int, addr2: Int): Boolean {
        return (addr1 and 0xff00) != (addr2 and 0xff00)
    }

    fun haltCycles(cycles: Int) {
        cyclesToHalt += cycles
    }

    // Interrupt vector fetches update the data bus, just like normal reads.
    // The 3 pushes go through push() which already steps the PPU; the 2 vector
    // reads use loadFromCartridge() directly and need explicit PPU steps.
    fun doNonMaskableInterrupt(status: Int) {
        if (nes.mmap == null) return

        // Cycles 1-2: internal operations (dummy reads of PC on real hardware).
        // Real bus cycles that advance the PPU; read values are discarded.
        instrBusCycles++
        nes.ppu.advanceDots(3)
        instrBusCycles++
        nes.ppu.advanceDots(3)

        REG_PC_NEW++
        push((REG_PC_NEW shr 8) and 0xff)
        push(REG_PC_NEW and 0xff)
        F_INTERRUPT_NEW = 1
        push(status)

        dataBus = loadFromCartridge(0xfffa)
        instrBusCycles++
        nes.ppu.advanceDots(3)
        val lo = dataBus
        dataBus = loadFromCartridge(0xfffb)
        instrBusCycles++
        nes.ppu.advanceDots(3)
        REG_PC_NEW = lo or (dataBus shl 8)
        REG_PC_NEW--
    }

    fun doResetInterrupt() {
        dataBus = loadFromCartridge(0xfffc)
        instrBusCycles++
        nes.ppu.advanceDots(3)
        val lo = dataBus
        dataBus = loadFromCartridge(0xfffd)
        instrBusCycles++
        nes.ppu.advanceDots(3)
        REG_PC_NEW = lo or (dataBus shl 8)
        REG_PC_NEW--
    }

    fun doIrq(status: Int) {
        REG_PC_NEW++
        push((REG_PC_NEW shr 8) and 0xff)
        push(REG_PC_NEW and 0xff)
        push(status)
        F_INTERRUPT_NEW = 1
        F_BRK_NEW = 0

        dataBus = loadFromCartridge(0xfffe)
        instrBusCycles++
        nes.ppu.advanceDots(3)
        val lo = dataBus
        dataBus = loadFromCartridge(0xffff)
        instrBusCycles++
        nes.ppu.advanceDots(3)
        REG_PC_NEW = lo or (dataBus shl 8)
        REG_PC_NEW--
    }

    fun getStatus(): Int {
        // F_ZERO is 0 when the Z flag is set, non-zero when clear
        return (
            F_CARRY or
                ((if (F_ZERO == 0) 1 else 0) shl 1) or
                (F_INTERRUPT shl 2) or
                (F_DECIMAL shl 3) or
                (F_BRK shl 4) or
                (F_NOTUSED shl 5) or
                (F_OVERFLOW shl 6) or
                (F_SIGN shl 7)
            )
    }

    fun setStatus(st: Int) {
        F_CARRY = st and 1
        // F_ZERO uses inverted encoding: 0 means Z is set
        F_ZERO = if (((st shr 1) and 1) == 1) 0 else 1
        F_INTERRUPT = (st shr 2) and 1
        F_DECIMAL = (st shr 3) and 1
        F_BRK = (st shr 4) and 1
        F_NOTUSED = (st shr 5) and 1
        F_OVERFLOW = (st shr 6) and 1
        F_SIGN = (st shr 7) and 1
    }

    // Set status flags from a value pulled off the stack (PLP, RTI). Bits 4
    // (B) and 5 (unused) don't exist as physical flags and are ignored.
    fun setStatusFromStack(st: Int) {
        F_CARRY = st and 1
        F_ZERO = if (((st shr 1) and 1) == 1) 0 else 1
        F_INTERRUPT = (st shr 2) and 1
        F_DECIMAL = (st shr 3) and 1
        F_OVERFLOW = (st shr 6) and 1
        F_SIGN = (st shr 7) and 1
    }
}
