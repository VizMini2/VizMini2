// Árbol de prueba con Groot2Publisher para BehaviorTreePcTest (puerto 1667 por defecto).
// Se repite sin parar: una secuencia con un fallback, nodos que tardan y uno que falla, y en la
// pizarra un contador de vueltas (loops), un texto (target) y un número (speed).
// Compilar: tools/bt_groot/build.sh. Uso: tools/bt_groot/bt_groot_server [puerto]
#include <behaviortree_cpp/bt_factory.h>
#include <behaviortree_cpp/loggers/groot2_publisher.h>
#include <chrono>
#include <cstdlib>
#include <thread>

using namespace std::chrono_literals;

static const char* kXml = R"(
<root BTCPP_format="4" main_tree_to_execute="Main">
  <BehaviorTree ID="Main">
    <Sequence name="patrol">
      <Script name="count" code=" loops := loops + 1 ; target := 'dock' ; speed := 0.5 "/>
      <Fallback name="find_target">
        <AlwaysFailure name="target_visible"/>
        <Wait name="search" ms="600"/>
      </Fallback>
      <Wait name="approach" ms="800"/>
      <SubTree ID="Dock"/>
    </Sequence>
  </BehaviorTree>
  <BehaviorTree ID="Dock">
    <Sequence name="dock">
      <Wait name="align" ms="500"/>
      <AlwaysSuccess name="charge"/>
    </Sequence>
  </BehaviorTree>
</root>)";

class Wait : public BT::StatefulActionNode {
 public:
  Wait(const std::string& name, const BT::NodeConfig& config) : BT::StatefulActionNode(name, config) {}
  static BT::PortsList providedPorts() { return {BT::InputPort<int>("ms")}; }
  BT::NodeStatus onStart() override {
    end_ = std::chrono::steady_clock::now() + std::chrono::milliseconds(getInput<int>("ms").value_or(500));
    return BT::NodeStatus::RUNNING;
  }
  BT::NodeStatus onRunning() override {
    return std::chrono::steady_clock::now() >= end_ ? BT::NodeStatus::SUCCESS : BT::NodeStatus::RUNNING;
  }
  void onHalted() override {}

 private:
  std::chrono::steady_clock::time_point end_;
};

int main(int argc, char** argv) {
  unsigned port = argc > 1 ? std::atoi(argv[1]) : 1667;
  BT::BehaviorTreeFactory factory;
  factory.registerNodeType<Wait>("Wait");
  factory.registerBehaviorTreeFromText(kXml);
  auto tree = factory.createTree("Main");
  tree.rootBlackboard()->set("loops", 0);
  BT::Groot2Publisher publisher(tree, port);
  while (true) {
    tree.tickWhileRunning(50ms);
    std::this_thread::sleep_for(300ms);
  }
}
