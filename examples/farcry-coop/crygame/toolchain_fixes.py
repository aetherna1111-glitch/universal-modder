"""Compile fixes so the SDK 1.4 CryGame sources build with clang-cl (no behaviour changes).

MSVC accepts a few constructs that clang rejects. Each fix is an exact (old -> new) text replacement;
the script fails loudly if a pattern is missing so a changed SDK checkout cannot be patched silently.
Usage: toolchain_fixes.py <Solution1 dir>
"""
import os
import sys

FIXES = [
    ("CryCommon/Cry_Vector3.h", "inline Vec3_tpl<f32>::Vec3_tpl(type_min)", "template<> inline Vec3_tpl<f32>::Vec3_tpl(type_min)"),
    ("CryCommon/Cry_Vector3.h", "inline Vec3_tpl<f32>::Vec3_tpl(type_max)", "template<> inline Vec3_tpl<f32>::Vec3_tpl(type_max)"),
    ("CryCommon/Cry_Vector3.h", "inline Vec3_tpl<f64>::Vec3_tpl(type_min)", "template<> inline Vec3_tpl<f64>::Vec3_tpl(type_min)"),
    ("CryCommon/Cry_Vector3.h", "inline Vec3_tpl<f64>::Vec3_tpl(type_max)", "template<> inline Vec3_tpl<f64>::Vec3_tpl(type_max)"),
    # Friend functions found only through MSVC's old "friend injection": redeclare them at namespace scope.
    ("CryCommon/Cry_Vector3.h", "\t\treturn i - n * (2.f * ((n|i) - d));\n\t}\n};\n",
     "\t\treturn i - n * (2.f * ((n|i) - d));\n\t}\n};\n"
     "Plane GetPlane(const Vec3 &normal, const Vec3 &point);\n"
     "Plane GetPlane(const Vec3 &v0, const Vec3 &v1, const Vec3 &v2);\n"),
    ("CryCommon/Cry_Matrix.h", "typedef Matrix44_tpl<real,4,1> Matrix44_f64;\n",
     "typedef Matrix44_tpl<real,4,1> Matrix44_f64;\nMatrix44 GetTransposed44(const Matrix44 &m);\n"),
    ("CryCommon/ISystem.h", "struct XDOM::IXMLDOMDocument;", "namespace XDOM { struct IXMLDOMDocument; }"),
    ("CryCommon/I3DEngine.h", "inline Vec3 SVariationValue<Vec3>::GetVariantValue() const",
     "template<> inline Vec3 SVariationValue<Vec3>::GetVariantValue() const"),
    ("CryCommon/Cry_Matrix.h", "Matrix44 GetTransposed44(const Matrix44 &m);\n",
     "Matrix44 GetTransposed44(const Matrix44 &m);\nMatrix44 GetTranslationMat(const Vec3 &v);\n"),
    # The LINUX branch of this macro already spells the explicit specialisations the standard way.
    ("CryCommon/_ScriptableEx.h", "#if defined(LINUX)\n\t#define _DECLARE_SCRIPTABLEEX",
     "#if defined(LINUX) || defined(__clang__)\n\t#define _DECLARE_SCRIPTABLEEX"),
    # MASM-style "$label" names in inline asm are not accepted by clang's MS asm parser.
    ("CryCommon/Cry_XOptimise.h", "$memcpy_", "memcpy_L_", "all"),
]


def main():
    root = sys.argv[1]
    for rel, old, new, *mode in FIXES:
        path = os.path.join(root, rel)
        text = open(path, encoding="latin-1").read()
        if mode == ["all"]:
            if old not in text and new not in text:
                sys.exit(f"toolchain_fixes: pattern not found in {rel}: {old!r}")
            open(path, "w", encoding="latin-1", newline="").write(text.replace(old, new))
            continue
        if new in text:
            continue
        if text.count(old) != 1:
            sys.exit(f"toolchain_fixes: pattern not found exactly once in {rel}: {old!r}")
        open(path, "w", encoding="latin-1", newline="").write(text.replace(old, new))
    print(f"toolchain_fixes: {len(FIXES)} fixes applied")


if __name__ == "__main__":
    main()
