package com.scriptacus.riderunrealangelscript.navigation

import com.intellij.testFramework.UsefulTestCase

/**
 * What C++ navigation is allowed to consider a declaration.
 *
 * These are the cases plain text search got wrong on real Unreal headers: an engine header declares
 * several classes that share member names, forward declarations outnumber real ones, and half the
 * mentions of a name are in comments.
 */
class CppSourceSearchTest : UsefulTestCase() {

    private val actorHeader = """
        // A comment mentioning class AActor : public UObject should never win.
        class UWorld;
        class AActor;

        UCLASS(Blueprintable)
        class ENGINE_API AActor : public UObject
        {
            GENERATED_BODY()

        public:
            UPROPERTY(EditAnywhere, Category="Transform")
            uint8 bHidden : 1;

            UPROPERTY()
            float CustomTimeDilation;

            UFUNCTION(BlueprintCallable)
            FVector GetActorLocation() const;

            virtual void Tick(float DeltaSeconds);

        private:
            void ApplyHidden(uint8 bHidden);
        };

        class ENGINE_API UActorComponent : public UObject
        {
            GENERATED_BODY()

            virtual void Tick(float DeltaSeconds);
        };
    """.trimIndent()

    private fun assertAt(text: String, offset: Int, expected: String) {
        assertTrue("expected '$expected' at $offset, got '${text.drop(offset).take(40)}'", offset >= 0)
        assertTrue("expected '$expected' at $offset, got '${text.drop(offset).take(40)}'", text.startsWith(expected, offset))
    }

    fun testTypeDeclarationBeatsForwardDeclarationsAndComments() {
        val source = CppSource(actorHeader)
        val offset = source.findTypeDeclaration("AActor")

        assertAt(actorHeader, offset, "AActor : public UObject")
    }

    fun testTypeIsNotFoundWhenOnlyForwardDeclared() {
        assertEquals(-1, CppSource("class UWorld;\nclass AActor;\n").findTypeDeclaration("AActor"))
    }

    /** `class AActor* Owner;` names the type, it does not declare it. */
    fun testElaboratedTypeSpecifierIsNotADeclaration() {
        val text = "struct FFoo\n{\n    class AActor* Owner;\n    friend class AActor;\n};\n"

        assertEquals(-1, CppSource(text).findTypeDeclaration("AActor"))
    }

    fun testEnumClassIsADeclaration() {
        val text = "UENUM()\nenum class EDrawDebugTrace : uint8\n{\n    None,\n};\n"

        assertAt(text, CppSource(text).findTypeDeclaration("EDrawDebugTrace"), "EDrawDebugTrace : uint8")
    }

    /**
     * The headline case: two classes in one header declare `Tick`, and the owner decides which one
     * is meant. Text search took whichever came first.
     */
    fun testMemberIsFoundInsideItsOwnClass() {
        val source = CppSource(actorHeader)

        val actorTick = source.findMemberDeclaration("AActor", "Tick")
        val componentTick = source.findMemberDeclaration("UActorComponent", "Tick")

        assertAt(actorHeader, actorTick, "Tick(float DeltaSeconds)")
        assertAt(actorHeader, componentTick, "Tick(float DeltaSeconds)")
        assertTrue("the component's Tick is the later one", componentTick > actorTick)
        assertTrue(actorTick > actorHeader.indexOf("class ENGINE_API AActor"))
        assertTrue(actorTick < actorHeader.indexOf("class ENGINE_API UActorComponent"))
    }

    fun testFunctionUnderUFunctionMacroIsFound() {
        val source = CppSource(actorHeader)

        assertAt(actorHeader, source.findMemberDeclaration("AActor", "GetActorLocation"), "GetActorLocation() const")
    }

    /** Properties were unreachable before: only names followed by `(` were ever matched. */
    fun testPropertiesAreFound() {
        val source = CppSource(actorHeader)

        assertAt(actorHeader, source.findMemberDeclaration("AActor", "bHidden"), "bHidden : 1")
        assertAt(actorHeader, source.findMemberDeclaration("AActor", "CustomTimeDilation"), "CustomTimeDilation;")
    }

    /** `bHidden` is also a parameter name further down; the UPROPERTY declaration outranks it. */
    fun testParameterOfTheSameNameIsNotTheDeclaration() {
        val source = CppSource(actorHeader)

        assertTrue(source.findMemberDeclaration("AActor", "bHidden") < actorHeader.indexOf("ApplyHidden"))
    }

    fun testCallsAndAssignmentsAreNotDeclarations() {
        val text = """
            void AFoo::Run()
            {
                Health = 5;
                this->Health = 6;
                SetHealth(Health);
                return Health;
            }
        """.trimIndent()

        assertEquals(-1, CppSource(text).findMemberDeclaration(null, "Health"))
    }

    fun testCommentsAndStringsAreNotSearched() {
        val text = """
            // int32 Health;
            /* float Health; */
            const char* Doc = "int32 Health;";
        """.trimIndent()

        assertEquals(-1, CppSource(text).findMemberDeclaration(null, "Health"))
    }

    fun testUnownedSearchFindsAFreeFunction() {
        val text = "void PrintToScreen(const FString& Message);\n"

        assertAt(text, CppSource(text).findMemberDeclaration(null, "PrintToScreen"), "PrintToScreen(const FString&")
    }

    /** UE5's math types are aliases produced by a macro; `Vector.h` never spells `FVector`. */
    fun testTypeAliasIsADeclaration() {
        val text = "namespace UE::Math { template<typename T> struct TVector; }\n" +
            "using FVector = UE::Math::TVector<double>;\n"

        assertAt(text, CppSource(text).findTypeDeclaration("FVector"), "FVector = UE::Math")
    }

    /** Blueprint-facing enums are wrapped: `namespace EDrawDebugTrace { enum Type { ... } }`. */
    fun testNamespaceWrappedEnumIsADeclaration() {
        val text = "UENUM()\nnamespace EDrawDebugTrace\n{\n    enum Type\n    {\n        None,\n    };\n}\n"

        assertAt(text, CppSource(text).findTypeDeclaration("EDrawDebugTrace"), "EDrawDebugTrace\n")
    }

    fun testAlignmentBetweenKeywordAndNameIsAllowed() {
        val text = "template<typename T>\nstruct alignas(alignof(TQuat<T>)) TTransform\n{\n    TQuat<T> Rotation;\n};\n"
        val source = CppSource(text)

        assertAt(text, source.findTypeDeclaration("TTransform"), "TTransform\n")
        assertAt(text, source.findMemberDeclaration("TTransform", "Rotation"), "Rotation;")
    }

    /**
     * Script-visible global functions exist only as the string the plugin binds them with, so the
     * literal is the declaration - but only where something is actually being bound.
     */
    fun testScriptBindingIsFoundInsideTheBoundSignature() {
        val text = """
            FAngelscriptBinds::BindGlobalFunction(
                "void Print(const FString& Text, float32 Duration = 5.f)",
                [](const FString& Text, float Duration)
            {
                UKismetSystemLibrary::PrintString(Text);
            });
        """.trimIndent()

        assertEquals(-1, CppSource(text).findMemberDeclaration(null, "Print"))
        assertAt(text, CppSource(text).findScriptBinding("Print"), "Print(const FString& Text")
    }

    fun testUnrelatedStringLiteralIsNotABinding() {
        val text = "UE_LOG(LogTemp, Warning, TEXT(\"void Print(int32 Value)\"));\n"

        assertEquals(-1, CppSource(text).findScriptBinding("Print"))
    }

    fun testTypePrefixIsStrippedForFileNames() {
        assertEquals(listOf("Actor.h", "AActor.h"), CppFileRanking.likelyFileNames("AActor"))
        assertEquals(listOf("HitResult.h", "FHitResult.h"), CppFileRanking.likelyFileNames("FHitResult"))
        // Not a prefix: the second character has to be upper case.
        assertEquals(listOf("Actor.h"), CppFileRanking.likelyFileNames("Actor"))
    }

    fun testRankingPrefersHeadersNamedAfterTheTypeInPublicFolders() {
        val public = "F:/UE/Engine/Source/Runtime/Engine/Public/GameFramework/Actor.h"
        val private = "F:/UE/Engine/Source/Runtime/Engine/Private/GameFramework/Actor.h"
        val unrelated = "F:/UE/Engine/Source/Runtime/Engine/Public/EngineTypes.h"
        val implementation = "F:/UE/Engine/Source/Runtime/Engine/Private/Actor.cpp"

        assertTrue(CppFileRanking.rank(public, "AActor") < CppFileRanking.rank(private, "AActor"))
        assertTrue(CppFileRanking.rank(private, "AActor") < CppFileRanking.rank(unrelated, "AActor"))
        assertTrue(CppFileRanking.rank(unrelated, "AActor") < CppFileRanking.rank(implementation, "AActor"))
    }

    /**
     * Both measured on a real project, where the lookup landed in an unrelated header that happened
     * to declare a `GetRotator` or a `SetTransform` of its own.
     */
    fun testRankingPrefersAFileSharingAWordWithTheType() {
        val fragments = "F:/UE/Engine/Plugins/Runtime/MassEntity/Source/MassCommon/Public/MassCommonFragments.h"
        val unrelated = "F:/Project/Source/F1Game/System/Components/Public/F1ComponentLibrary.h"
        assertTrue(CppFileRanking.rank(fragments, "FTransformFragment") < CppFileRanking.rank(unrelated, "FTransformFragment"))

        val math = "F:/UE/Engine/Source/Runtime/Core/Public/Math/TransformNonVectorized.h"
        val flow = "F:/Project/Plugins/FlowGraph/Source/Flow/Public/Types/FlowDataPinProperties.h"
        assertTrue(CppFileRanking.rank(math, "TTransform") < CppFileRanking.rank(flow, "TTransform"))
    }

    /**
     * The check that catches the wrong jump: measured on a real project, `FTransformFragment`'s
     * `SetTransform` was found in a header declaring `FF1ComponentNetTransform::SetTransform`,
     * and the only thing that tells them apart is which class body the line sits in.
     */
    fun testEnclosingTypeIsTheInnermostOneAround() {
        val source = CppSource(actorHeader)

        val actorTick = source.findMemberDeclaration("AActor", "Tick")
        val componentTick = source.findMemberDeclaration("UActorComponent", "Tick")

        assertEquals("AActor", source.enclosingTypeAt(actorTick))
        assertEquals("UActorComponent", source.enclosingTypeAt(componentTick))
    }

    fun testEnclosingTypeIsNullOutsideAnyType() {
        val text = "void PrintToScreen(const FString& Message);\n\nstruct FFoo\n{\n    void Inside();\n};\n"
        val source = CppSource(text)

        assertNull(source.enclosingTypeAt(text.indexOf("PrintToScreen")))
        assertEquals("FFoo", source.enclosingTypeAt(text.indexOf("Inside")))
    }

    /** A function body inside the class does not become the enclosing scope of what follows. */
    fun testInlineBodiesDoNotConfuseTheEnclosingType() {
        val text = """
            struct FTransformFragment : public FMassFragment
            {
                const FTransform& GetTransform() const
                {
                    return Transform;
                }

                void SetTransform(const FTransform& InTransform)
                {
                    Transform = InTransform;
                }
            };
        """.trimIndent()

        assertEquals("FTransformFragment", CppSource(text).enclosingTypeAt(text.indexOf("SetTransform")))
    }

    fun testMentionsSeesCodeOnly() {
        assertTrue(CppSource("void Take(const FTransform& T);\n").mentions("FTransform"))
        assertFalse(CppSource("// takes an FTransform one day\n").mentions("FTransform"))
        assertFalse(CppSource("void Take(const FTransformFragment& F);\n").mentions("FTransform"))
    }

    fun testBuildOutputIsNeverACandidate() {
        assertTrue(CppFileRanking.isBuildArtifact("F:/Project/Intermediate/Build/Win64/UHT/Actor.generated.h"))
        assertTrue(CppFileRanking.isBuildArtifact("F:\\Project\\Binaries\\Win64\\Foo.h"))
        assertFalse(CppFileRanking.isBuildArtifact("F:/UE/Engine/Source/Runtime/Engine/Public/Actor.h"))
    }
}
