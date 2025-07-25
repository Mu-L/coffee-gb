package eu.rekawek.coffeegb.swing.emulator.session

import com.google.common.annotations.VisibleForTesting
import eu.rekawek.coffeegb.Gameboy
import eu.rekawek.coffeegb.Gameboy.TICKS_PER_FRAME
import eu.rekawek.coffeegb.controller.Button
import eu.rekawek.coffeegb.controller.Joypad
import eu.rekawek.coffeegb.events.Event
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.memento.Memento
import eu.rekawek.coffeegb.serial.Peer2PeerSerialEndpoint
import eu.rekawek.coffeegb.swing.events.register
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*
import kotlin.math.max
import kotlin.math.min

class StateHistory(
    private val mainRom: File,
    private val peerRom: ByteArray,
    private val peerBattery: ByteArray?
) {

  private val states = LinkedList<State>()

  private val patches = mutableListOf<Patch>()

  var debugEventBus: EventBus? = null

  @Synchronized
  fun addState(
      frame: Long,
      mainInput: Input,
      mainMemento: Memento<Gameboy>,
      secondaryMemento: Memento<Gameboy>,
      mainLinkMemento: Memento<Peer2PeerSerialEndpoint>,
      secondaryLinkMemento: Memento<Peer2PeerSerialEndpoint>,
  ) {
    states.add(
        State(
            frame, mainInput, mainMemento, secondaryMemento, mainLinkMemento, secondaryLinkMemento))
    while (states.size > 60 * 5) {
      states.removeFirst()
    }
  }

  @Synchronized
  fun addSecondaryInput(
      frame: Long,
      secondaryInput: Input,
  ) {
    patches.add(Patch(frame, secondaryInput))
  }

  @Synchronized
  fun merge(): Boolean {
    if (patches.isEmpty() || states.isEmpty()) {
      return false
    }
    val baseFrame = min(patches.first().frame, states.last().frame)
    val toFrame = max(states.last().frame, patches.last().frame)
    LOG.atDebug().log("Rebasing from $baseFrame to $toFrame")

    val mainInputs = states.groupBy { it.frame }.mapValues { it.value.first().mainInput }
    val secondaryInputs = patches.groupBy { it.frame }.mapValues { it.value.first().secondaryInput }

    if (baseFrame < states.first().frame) {
      throw IllegalStateException("No frame $baseFrame")
    }
    val baseState =
        states.firstOrNull { it.frame == baseFrame }
            ?: throw IllegalStateException("No frame $baseFrame")

    val mainGameboy = Gameboy(CartridgeUtils.createCartridge(mainRom))
    val secondaryGameboy = Gameboy(CartridgeUtils.createCartridge(peerRom, peerBattery))
    val mainLink = Peer2PeerSerialEndpoint()
    val secondaryLink = Peer2PeerSerialEndpoint()
    mainLink.init(secondaryLink)

    val mainEventBus = EventBus()
    val secondaryEventBus = EventBus()
    mainEventBus.register<Joypad.JoypadPressEvent> {
      debugEventBus?.post(GameboyJoypadPressEvent(it.button, it.tick, 0))
    }
    secondaryEventBus.register<Joypad.JoypadPressEvent> {
      debugEventBus?.post(GameboyJoypadPressEvent(it.button, it.tick, 1))
    }

    mainGameboy.init(mainEventBus, mainLink, null)
    secondaryGameboy.init(secondaryEventBus, secondaryLink, null)

    mainGameboy.restoreFromMemento(baseState.mainMemento)
    secondaryGameboy.restoreFromMemento(baseState.secondaryMemento)
    mainLink.restoreFromMemento(baseState.mainLinkMemento)
    secondaryLink.restoreFromMemento(baseState.secondaryLinkMemento)

    states.clear()
    patches.clear()

    for (i in (baseFrame..toFrame + 1)) {
      val mainInput = mainInputs[i] ?: Input(emptyList(), emptyList())
      states.add(
          State(
              i,
              mainInput,
              mainGameboy.saveToMemento(),
              secondaryGameboy.saveToMemento(),
              mainLink.saveToMemento(),
              secondaryLink.saveToMemento()))

      if (i <= toFrame) {
        mainInput.send(mainEventBus)
        val secondaryInput = secondaryInputs[i]
        if (secondaryInput != null) {
          LOG.atDebug().log("Sending secondary input {} on frame {}", secondaryInput, i)
          secondaryInput.send(secondaryEventBus)
        }

        repeat(TICKS_PER_FRAME) {
          mainGameboy.tick()
          secondaryGameboy.tick()
        }
      }
    }
    LOG.atDebug().log("Rebase from $baseFrame to $toFrame completed.")
    return true
  }

  fun getHead() = states.last()

  data class State(
      val frame: Long,
      val mainInput: Input,
      val mainMemento: Memento<Gameboy>,
      val secondaryMemento: Memento<Gameboy>,
      val mainLinkMemento: Memento<Peer2PeerSerialEndpoint>,
      val secondaryLinkMemento: Memento<Peer2PeerSerialEndpoint>,
  )

  private data class Patch(
      val frame: Long,
      val secondaryInput: Input,
  )

  @VisibleForTesting
  internal data class GameboyJoypadPressEvent(
      val button: Button,
      val tick: Long,
      val gameboy: Int
  ) : Event

  companion object {
    val LOG: Logger = LoggerFactory.getLogger(StateHistory::class.java)
  }
}
