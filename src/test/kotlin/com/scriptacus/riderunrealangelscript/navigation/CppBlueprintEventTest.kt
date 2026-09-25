package com.scriptacus.riderunrealangelscript.navigation

import com.intellij.testFramework.UsefulTestCase

/**
 * Which declarations in a header are blueprint events a script class can override, and what script
 * calls them.
 */
class CppBlueprintEventTest : UsefulTestCase() {

    /** Shaped like a real project header: a struct first, a native event with its C++ half, then events. */
    private val logicHeader = """
        struct FDoorUser;

        USTRUCT(BlueprintType)
        struct FDoorAnimationInfo
        {
            GENERATED_BODY()

            UPROPERTY(EditAnywhere, meta = (DisplayName = "Open", AllowedClasses = "/Script/Engine.AnimMontage"))
            FSoftObjectPath OpenMontage = nullptr;
        };

        /**
         *
         */
        UCLASS(Abstract, BlueprintType, meta = (DisplayName = "Door logic (do not pick)"))
        class MYGAME_API UDoorLogicBase : public UObject, public FTickableGameObject
        {
            GENERATED_BODY()

        public:
            UFUNCTION(ScriptCallable)
            void SetTickType(int32 InTickType);

            UFUNCTION(BlueprintNativeEvent, ScriptCallable)
            void Open(ACharacter* Opener, const FGameplayTag Reason);
            virtual void Open_Implementation(ACharacter* Opener, const FGameplayTag Reason);

            /**
             * @brief Called on both the server and the client.
             */
            UFUNCTION(BlueprintImplementableEvent, ScriptCallable)
            void OnOpened(FDoorUser& User, FDoorUser& Door);

            UFUNCTION(BlueprintImplementableEvent, ScriptCallable)
            void TickDoor(float DeltaTime);
        };
    """.trimIndent()

    private fun event(name: String, owner: String? = "AActor", meta: String? = null) =
        CppBlueprintEvent(owner, name, 0, implementable = true, scriptNameMeta = meta)

    fun testEventsAreFoundWithTheirClass() {
        val events = CppSource(logicHeader).findBlueprintEvents()

        assertEquals(listOf("Open", "OnOpened", "TickDoor"), events.map { it.name })
        assertTrue(events.all { it.owner == "UDoorLogicBase" })
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
        assertEquals(listOf("OnOpened"), event("OnOpened").scriptNames())
        // Nothing would be left, so the C++ name is the only one.
        assertEquals(listOf("Receive"), event("Receive").scriptNames())
    }

    fun testScriptNameMetaWins() {
        assertEquals(listOf("OnHit", "ReceiveHit"), event("ReceiveHit", meta = "OnHit").scriptNames())
    }
}
