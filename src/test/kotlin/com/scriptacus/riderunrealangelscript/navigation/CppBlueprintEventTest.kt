package com.scriptacus.riderunrealangelscript.navigation

import com.intellij.testFramework.UsefulTestCase

/**
 * Which declarations in a header are blueprint events a script class can override, and what script
 * calls them.
 */
class CppBlueprintEventTest : UsefulTestCase() {

    /** Shaped like a real project header: a struct first, a native event with its C++ half, then events. */
    private val logicHeader = """
        struct FF1EntityView;

        USTRUCT(BlueprintType)
        struct FF1InteractionMontageInfo
        {
            GENERATED_BODY()

            UPROPERTY(EditAnywhere, meta = (DisplayName = "FPP", AllowedClasses = "/Script/Engine.AnimMontage"))
            FSoftObjectPath InteractionMontageFPP = nullptr;
        };

        /**
         *
         */
        UCLASS(Abstract, BlueprintType, meta = (DisplayName = "Interaction logic (do not pick)"))
        class F1GAME_API UF1InteractableLogicBase : public UObject, public FTickableGameObject
        {
            GENERATED_BODY()

        public:
            UFUNCTION(ScriptCallable)
            void SetTickableTickTypeValue(int32 InTickType);

            UFUNCTION(BlueprintNativeEvent, ScriptCallable)
            void ExecuteAbility(AF1Character* TargetCharacter, const FGameplayTag GATag);
            virtual void ExecuteAbility_Implementation(AF1Character* TargetCharacter, const FGameplayTag GATag);

            /**
             * @brief This function will be called on both DS and Client.
             */
            UFUNCTION(BlueprintImplementableEvent, ScriptCallable)
            void ExecuteLogic(FF1EntityView& CharacterEntity, FF1EntityView& InteractableEntity);

            UFUNCTION(BlueprintImplementableEvent, ScriptCallable)
            void TickLogic(float DeltaTime);
        };
    """.trimIndent()

    private fun event(name: String, owner: String? = "AActor", meta: String? = null) =
        CppBlueprintEvent(owner, name, 0, implementable = true, scriptNameMeta = meta)

    fun testEventsAreFoundWithTheirClass() {
        val events = CppSource(logicHeader).findBlueprintEvents()

        assertEquals(listOf("ExecuteAbility", "ExecuteLogic", "TickLogic"), events.map { it.name })
        assertTrue(events.all { it.owner == "UF1InteractableLogicBase" })
        assertEquals(listOf(false, true, true), events.map { it.implementable })
        for (event in events) {
            assertTrue(logicHeader.startsWith(event.name + "(", event.nameOffset))
        }
    }

    fun testEachEventHasTheClassWhoseBodyDeclaresIt() {
        val text = """
            UCLASS()
            class UFirst : public UObject
            {
                UFUNCTION(BlueprintImplementableEvent)
                void OnHit();
            };

            UCLASS()
            class USecond : public UFirst
            {
                UFUNCTION(BlueprintImplementableEvent)
                void OnHit();
            };
        """.trimIndent()

        assertEquals(listOf("UFirst", "USecond"), CppSource(text).findBlueprintEvents().map { it.owner })
    }

    fun testOnlyTheMacrosOwnSpecifiersCount() {
        val text = """
            class UThing : public UObject
            {
                // UFUNCTION(BlueprintImplementableEvent)
                void Commented();

                UFUNCTION(BlueprintCallable, meta = (ToolTip = "Not a BlueprintImplementableEvent"))
                void Callable();

                UFUNCTION(BlueprintCallable)
                void Plain(); // BlueprintNativeEvent
            };
        """.trimIndent()

        assertEmpty(CppSource(text).findBlueprintEvents())
    }

    fun testSpecifiersAreReadWithoutRegardToCase() {
        val text = "class UThing { UFUNCTION(blueprintimplementableevent) void OnThing(); };"

        assertEquals(listOf("OnThing"), CppSource(text).findBlueprintEvents().map { it.name })
    }

    fun testAMacroBeforeTheDeclarationIsNotItsName() {
        val text = """
            class UThing : public UObject
            {
                UFUNCTION(BlueprintImplementableEvent, meta = (DeprecatedFunction))
                UE_DEPRECATED(5.1, "Use OnThing instead")
                void OnOldThing(int32 Value);
            };
        """.trimIndent()

        assertEquals(listOf("OnOldThing"), CppSource(text).findBlueprintEvents().map { it.name })
    }

    fun testScriptNameMetaIsRead() {
        val text = """
            class UThing : public UObject
            {
                UFUNCTION(BlueprintImplementableEvent, meta = (ScriptName = "OnHit;Hit", DisplayName = "Hit"))
                void ReceiveHit();

                UFUNCTION(BlueprintNativeEvent, Meta = (ScriptMethod = Touched))
                void ReceiveTouch();
            };
        """.trimIndent()

        assertEquals(listOf("OnHit", "Touched"), CppSource(text).findBlueprintEvents().map { it.scriptNameMeta })
    }

    fun testScriptDropsTheBlueprintPrefixes() {
        assertEquals(listOf("BeginPlay", "ReceiveBeginPlay"), event("ReceiveBeginPlay").scriptNames())
        assertEquals(listOf("Notify", "Received_Notify"), event("Received_Notify").scriptNames())
        assertEquals(listOf("OnReset", "K2_OnReset"), event("K2_OnReset").scriptNames())
        // One after the other, in the order the AngelScript plugin removes them.
        assertEquals(listOf("Spawned", "BP_ReceiveSpawned"), event("BP_ReceiveSpawned").scriptNames())
        assertEquals(listOf("Hit", "receiveHit"), event("receiveHit").scriptNames())
    }

    fun testANameWithoutPrefixIsKept() {
        assertEquals(listOf("ExecuteLogic"), event("ExecuteLogic").scriptNames())
        // Nothing would be left, so the C++ name is the only one.
        assertEquals(listOf("Receive"), event("Receive").scriptNames())
    }

    fun testScriptNameMetaWins() {
        assertEquals(listOf("OnHit", "ReceiveHit"), event("ReceiveHit", meta = "OnHit").scriptNames())
    }
}
