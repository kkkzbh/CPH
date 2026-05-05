package org.kkkzbh.cph.submit

import org.kkkzbh.cph.CphCppStandard

internal enum class CphCfLanguage(val displayName: String, val defaultProgramTypeId: Int) {
    CPP_11("GNU G++11 5.1.0", 42),
    CPP_17("GNU G++17 7.3.0", 54),
    CPP_20("GNU G++20 13.2 (64 bit, winlibs)", 89),
    CPP_23("GNU G++23 14.2 (64 bit, msys2)", 91);

    companion object {
        fun fromCppStandard(standard: CphCppStandard): CphCfLanguage {
            return when (standard) {
                CphCppStandard.CPP11 -> CPP_11
                CphCppStandard.CPP17 -> CPP_17
                CphCppStandard.CPP20 -> CPP_20
                CphCppStandard.FOLLOW_TARGET,
                CphCppStandard.CPP23,
                CphCppStandard.CPP26,
                -> CPP_23
            }
        }
    }
}
